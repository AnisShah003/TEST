import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class CorporateFileService {
    private final LoggerService loggerService;
    private final BankProgramRepository bankProgramRepository;
    private final MandateRequestRepository mandateRequestRepository;
    private static final Set<Integer> SUCCESS_STATUSES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(5)));
    private static final Set<Integer> PENDING_STATUSES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(1, 2, 3, 8, 9, 11, 12)));
    private static final Set<Integer> REJECTED_STATUSES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(4, 6, 7, 10, 13)));

    public CorporateFileService(LoggerService loggerService,
                                BankProgramRepository bankProgramRepository,
                                MandateRequestRepository mandateRequestRepository) {
        this.loggerService = loggerService;
        this.bankProgramRepository = bankProgramRepository;
        this.mandateRequestRepository = mandateRequestRepository;
    }

    public Page<PhysicalCorporateFileDto> getCorporateFileList(Pageable pageable, Integer bankProgramId,
                                                               String entityName, String tspName, String fromDate, String toDate, String nachType, String mrfileName) {
        loggerService.info("Starting getCorporateFileList method", "getCorporateFileList", "");
        try {

            Sort sort = Sort.by(Sort.Direction.DESC, "createdAt");

            Optional<BankProgram> optionalProgram = bankProgramRepository
                    .findByBpExternalId(String.valueOf(bankProgramId));
            if (optionalProgram.isEmpty()) {
                loggerService.error(ErrorCode.getErrorMessage(ErrorCode.PM001), "getCorporateFileList", "");
                throw new ServiceException(ErrorCode.PM001);
            }

            BankProgram bankProgram = optionalProgram.get();
            BankEntityHdr bankEntityHdr = bankProgram.getBankEntityHdr();

            if (bankEntityHdr == null) {
                loggerService.error(ErrorCode.getErrorMessage(ErrorCode.PM002), "getCorporateFileList", "");
                throw new ServiceException(ErrorCode.PM002);
            }

            Integer bankEntityId = bankEntityHdr.getBeId();
            String bankEntityName = bankEntityHdr.getBeName();
            if (bankEntityName == null) {
                loggerService.error(ErrorCode.getErrorMessage(ErrorCode.PM003), "getCorporateFileList", "");
                throw new ServiceException(ErrorCode.PM003);
            }

            Specification<MandateRequest> spec = Specification
                    .where(MandateRequestSpecification.hasBankProgramId(bankEntityId))
                    .and(MandateRequestSpecification.hasEntityName(entityName))
                    .and(MandateRequestSpecification.hasTspName(tspName))
                    .and(MandateRequestSpecification.hasFileName(mrfileName))
                    .and(MandateRequestSpecification.hasMrPidIsNull())
                    .and(MasterSpecification.hasDateBetween("createdAt", fromDate, toDate));

            if (CommonUtil.checkNotNullEmpty(nachType)) {
                spec = spec.and(MandateRequestSpecification.hasPnachRequestType(nachType));
            } else {
                List<Integer> nachTypes = Arrays.asList(1, 2, 3);
                spec = spec.and(MandateRequestSpecification.hasRequestTypes(nachTypes));
            }
            Pageable pageRequest = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
            Page<MandateRequest> mandateRequests = mandateRequestRepository.findAll(spec, pageRequest);

            List<MandateRequest> mandateRequestList = mandateRequests.getContent();
            List<Integer> zipMrIds = mandateRequestList.stream()
                    .map(MandateRequest::getMrId)
                    .collect(Collectors.toList());

            Map<Integer, List<MandateRequest>> mrPIdAgainstList = zipMrIds.isEmpty()
                    ? Collections.emptyMap()
                    : mandateRequestRepository.findByMrPidIn(zipMrIds)
                            .stream()
                            .collect(Collectors.groupingBy(MandateRequest::getMrPid)); // xml records

            List<PhysicalCorporateFileDto> physicalCorporateDtos = new ArrayList<>(mandateRequestList.size());
            for (MandateRequest req : mandateRequestList) {
                PhysicalCorporateFileDto dto = new PhysicalCorporateFileDto();
                populateCommonFields(dto, req);

                List<MandateRequest> childRequests = mrPIdAgainstList.get(req.getMrId());
                List<MandateRequest> mandateRequestListForCounts = childRequests != null
                        ? childRequests
                        : Collections.singletonList(req);

                dto.setFileName(req.getMrFilename());
                if (childRequests != null) {
                    dto.setMrpId(req.getMrId());
                }
                populateCounts(dto, mandateRequestListForCounts);
                physicalCorporateDtos.add(dto);
            }
            loggerService.info("Returning corporate file", "getCorporateFileList", "");
            return new PageImpl<>(physicalCorporateDtos, pageRequest, mandateRequests.getTotalElements());
        } catch (Exception e) {
            loggerService.error("Error fetching corporate file", "getCorporateFileList", e.getMessage());
            throw new ServiceException(e.getMessage());
        }
    }

    private void populateCommonFields(PhysicalCorporateFileDto dto, MandateRequest req) {
        dto.setTspName(Optional.ofNullable(req.getTspLinkHdr())
                .map(TspLinkHdr::getTspName)
                .orElse(""));
        dto.setEntityName(req.getCorporateEntityHdr() != null
                ? req.getCorporateEntityHdr().getCeName()
                : "");
        dto.setLastUpdatedAt(DateUtil.formatToReadableDate(req.getUpdatedAt()));
        dto.setMrNachType((int) req.getMrNachType());
    }

    private void populateCounts(PhysicalCorporateFileDto dto, List<MandateRequest> requests) {
        int success = 0;
        int pending = 0;
        int rejected = 0;

        for (MandateRequest mr : requests) {
            int status = mr.getMrStatus();
            if (SUCCESS_STATUSES.contains(status)) {
                success++;
            } else if (PENDING_STATUSES.contains(status)) {
                pending++;
            } else if (REJECTED_STATUSES.contains(status)) {
                rejected++;
            }
        }
        dto.setTotalCount(requests.size());
        dto.setSuccessCount(success);
        dto.setPendingCount(pending);
        dto.setRejectedCount(rejected);
    }
}
