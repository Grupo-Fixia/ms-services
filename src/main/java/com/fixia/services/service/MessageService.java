package com.fixia.services.service;

import com.fixia.services.domain.ServiceContract;
import com.fixia.services.domain.ServiceMessage;
import com.fixia.services.domain.ServiceRequest;
import com.fixia.services.domain.ServiceRequestStatus;
import com.fixia.services.exception.ContractNotFoundException;
import com.fixia.services.exception.ConversationClosedException;
import com.fixia.services.exception.InvalidMessageException;
import com.fixia.services.exception.MessageConflictException;
import com.fixia.services.exception.ServiceRequestNotFoundException;
import com.fixia.services.repository.ServiceContractRepository;
import com.fixia.services.repository.ServiceMessageRepository;
import com.fixia.services.repository.ServiceRequestRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Chat de un servicio contratado (GC-527, contrato {@code docs/contracts/mensajeria.md}). Participan solo el cliente y el
 * técnico que ejecuta (GC-551): una empresa prestadora no lee los mensajes (RF-040). Cualquier otro, o una contratación
 * inexistente, recibe la misma 404. Se escribe mientras el servicio está contratado, en curso o finalizado por el
 * técnico; después del cierre (o si no llegó a contratarse) la conversación solo se lee.
 */
@Service
public class MessageService {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    /** Estados en los que todavía se pueden enviar mensajes. */
    static final Set<ServiceRequestStatus> OPEN = EnumSet.of(ServiceRequestStatus.CONTRACTED,
            ServiceRequestStatus.IN_PROGRESS, ServiceRequestStatus.TECHNICIAN_FINISHED);

    private final ServiceContractRepository contractRepository;
    private final ServiceRequestRepository requestRepository;
    private final ServiceMessageRepository messageRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public MessageService(ServiceContractRepository contractRepository, ServiceRequestRepository requestRepository,
                          ServiceMessageRepository messageRepository, TransactionTemplate transactionTemplate,
                          Clock clock) {
        this.contractRepository = contractRepository;
        this.requestRepository = requestRepository;
        this.messageRepository = messageRepository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /** La contratación que ejecuta el técnico; si no es suya, 404. */
    @Transactional(readOnly = true)
    public ServiceContract contractOfTechnician(UUID technicianId, UUID contractId) {
        return contractRepository.findByIdAndExecutorId(contractId, technicianId)
                .orElseThrow(ContractNotFoundException::new);
    }

    /** La contratación de la solicitud del cliente; si la solicitud no es suya o no se contrató, 404. */
    @Transactional(readOnly = true)
    public ServiceContract contractOfClient(UUID clientId, UUID serviceRequestId) {
        requestRepository.findById(serviceRequestId)
                .filter(request -> request.getClientId().equals(clientId))
                .orElseThrow(ServiceRequestNotFoundException::new);
        return contractRepository.findByServiceRequestId(serviceRequestId)
                .orElseThrow(ContractNotFoundException::new);
    }

    /**
     * Una página de la conversación en orden de envío. Sin {@code after}, desde el primer mensaje; con él, solo los
     * posteriores (así la app pregunta si llegó algo nuevo). {@code next} es la posición del último mensaje entregado,
     * o {@code after} si no hubo ninguno nuevo, para seguir preguntando desde ahí.
     */
    @Transactional(readOnly = true)
    public Page page(ServiceContract contract, String after, Integer limit) {
        int size = limit(limit);
        Position from = after == null || after.isBlank() ? null : Position.decode(after);
        PageRequest firstRows = PageRequest.of(0, size + 1);
        List<ServiceMessage> rows = new ArrayList<>(from == null
                ? messageRepository.findFirstPage(contract.getId(), firstRows)
                : messageRepository.findPageAfter(contract.getId(), from.createdAt(), from.id(), firstRows));
        boolean more = rows.size() > size;
        if (more) {
            rows.subList(size, rows.size()).clear();
        }
        String next = rows.isEmpty() ? (from == null ? null : from.encode()) : Position.of(rows.getLast()).encode();
        boolean open = requestRepository.findById(contract.getServiceRequestId())
                .map(request -> OPEN.contains(request.getStatus())).orElse(false);
        return new Page(rows, next, more, open);
    }

    /**
     * Envía un mensaje del participante al otro. Repetir el mismo envío con el mismo {@code clientMessageId} devuelve
     * el mensaje ya guardado ({@code created = false}) en lugar de duplicarlo; con otro texto, 409.
     *
     * @throws ConversationClosedException 409 si el servicio ya no admite mensajes
     * @throws InvalidMessageException     400 si el texto está vacío o es demasiado largo
     */
    public SendResult send(ServiceContract contract, UUID senderId, String body, UUID clientMessageId) {
        if (clientMessageId != null) {
            var existing = messageRepository.findByContractIdAndSenderIdAndClientMessageId(contract.getId(), senderId,
                    clientMessageId);
            if (existing.isPresent()) {
                return repeated(existing.get(), senderId, body);
            }
        }
        ServiceRequest request = requestRepository.findById(contract.getServiceRequestId())
                .orElseThrow(ContractNotFoundException::new);
        if (!OPEN.contains(request.getStatus())) {
            throw new ConversationClosedException(request.getStatus() == ServiceRequestStatus.CLOSED
                    ? "El servicio ya se cerró: la conversación se puede leer, pero no admite mensajes nuevos"
                    : "El servicio no admite mensajes en su estado actual");
        }
        ServiceMessage message;
        try {
            message = ServiceMessage.send(contract, senderId, body, clientMessageId, clock.instant());
        } catch (IllegalArgumentException e) {
            throw new InvalidMessageException(e.getMessage());
        }
        try {
            return new SendResult(transactionTemplate.execute(status -> messageRepository.saveAndFlush(message)),
                    true);
        } catch (DataIntegrityViolationException e) {
            // Dos envíos simultáneos con el mismo identificador: gana uno y el otro devuelve ese.
            ServiceMessage winner = messageRepository.findByContractIdAndSenderIdAndClientMessageId(contract.getId(),
                    senderId, clientMessageId).orElseThrow(() -> e);
            return repeated(winner, senderId, body);
        }
    }

    private static SendResult repeated(ServiceMessage existing, UUID senderId, String body) {
        if (!existing.isSameAs(senderId, body)) {
            throw new MessageConflictException("Ese identificador de mensaje ya se usó con otro texto");
        }
        return new SendResult(existing, false);
    }

    private static int limit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidMessageException("El límite debe estar entre 1 y " + MAX_LIMIT);
        }
        return limit;
    }

    /** Una página de mensajes; {@code open} dice si todavía se pueden enviar. */
    public record Page(List<ServiceMessage> messages, String next, boolean more, boolean open) {
    }

    public record SendResult(ServiceMessage message, boolean created) {
    }

    /** Posición opaca en la conversación: {@code createdAt|id} del último mensaje, en Base64 URL. */
    record Position(Instant createdAt, UUID id) {

        static Position of(ServiceMessage message) {
            return new Position(message.getCreatedAt(), message.getId());
        }

        String encode() {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((createdAt + "|" + id).getBytes(StandardCharsets.UTF_8));
        }

        static Position decode(String text) {
            try {
                String raw = new String(Base64.getUrlDecoder().decode(text.strip()), StandardCharsets.UTF_8);
                int separator = raw.indexOf('|');
                if (separator <= 0) {
                    throw new InvalidMessageException("El cursor no es válido");
                }
                return new Position(Instant.parse(raw.substring(0, separator)),
                        UUID.fromString(raw.substring(separator + 1)));
            } catch (IllegalArgumentException | DateTimeParseException e) {
                throw new InvalidMessageException("El cursor no es válido");
            }
        }
    }
}
