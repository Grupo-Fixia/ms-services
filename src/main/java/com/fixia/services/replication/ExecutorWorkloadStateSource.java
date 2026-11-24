package com.fixia.services.replication;

import com.fixia.services.domain.ProviderType;
import com.fixia.services.domain.ServiceRequestApplication;
import com.fixia.services.dto.CompanyExecutorWorkloadResponse;
import com.fixia.services.repository.ServiceRequestApplicationRepository;
import com.fixia.services.service.CompanyExecutorWorkloadService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Trabajo de cada técnico propuesto por una empresa (GC-547): por cada empresa, sus postulaciones pendientes y los
 * servicios en que fue el seleccionado. Es lo que respondía la ruta interna de GC-524 cuando un técnico pedía
 * desvincularse; ahora ms-users guarda esta copia y no llama a ms-services. Se calcula con
 * {@link CompanyExecutorWorkloadService}, la misma lógica de la ruta.
 */
@Component
public class ExecutorWorkloadStateSource implements StateSource {

    public static final String STATE_TYPE = "ejecutor.carga";
    public static final String TOPIC = "fixia.services.executor-workloads";

    record CompanyWorkload(UUID companyAdminUserId, List<UUID> pendingServiceRequestIds,
                           List<UUID> serviceRequestIds) {
    }

    record ExecutorWorkload(UUID technicianUserId, List<CompanyWorkload> companies) {
    }

    private final ServiceRequestApplicationRepository applications;
    private final CompanyExecutorWorkloadService workloads;

    public ExecutorWorkloadStateSource(ServiceRequestApplicationRepository applications,
                                       CompanyExecutorWorkloadService workloads) {
        this.applications = applications;
        this.workloads = workloads;
    }

    @Override
    public String stateType() {
        return STATE_TYPE;
    }

    @Override
    public String topic() {
        return TOPIC;
    }

    @Override
    public String aggregateType() {
        return "ExecutorWorkload";
    }

    @Override
    public Map<UUID, Object> currentStates() {
        Map<UUID, Set<UUID>> companiesByTechnician = new LinkedHashMap<>();
        for (ServiceRequestApplication application : applications.findAll()) {
            if (application.getProviderType() == ProviderType.COMPANY && application.getTechnicianId() != null) {
                companiesByTechnician.computeIfAbsent(application.getTechnicianId(), id -> new LinkedHashSet<>())
                        .add(application.getProviderId());
            }
        }
        Map<UUID, Object> states = new LinkedHashMap<>();
        companiesByTechnician.forEach((technician, companies) -> {
            List<CompanyWorkload> perCompany = new ArrayList<>();
            for (UUID company : companies) {
                CompanyExecutorWorkloadResponse workload = workloads.workload(company, technician);
                perCompany.add(new CompanyWorkload(company, workload.pendingServiceRequestIds(),
                        workload.serviceRequestIds()));
            }
            states.put(technician, new ExecutorWorkload(technician, perCompany));
        });
        return states;
    }
}
