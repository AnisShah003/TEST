import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CorporateFileService {
    private final LoggerService loggerService;
    private final BankProgramRepository bankProgramRepository;
    private final MandateRequestRepository mandateRequestRepository;

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
            Map<Integer, String> mrIdAndMrFileName = mandateRequestList.stream()
                    .collect(Collectors.toMap(MandateRequest::getMrId, MandateRequest::getMrFilename));

            List<MandateRequest> requestsForCounts = new ArrayList<>(mandateRequestList);
            if (CommonUtil.checkNotNullEmpty(mrfileName) && !mandateRequestList.isEmpty()) {
                // When filtering by a zip filename, include child XML records in counts.
                List<Integer> parentIds = mandateRequestList.stream()
                        .map(MandateRequest::getMrId)
                        .collect(Collectors.toList());
                Specification<MandateRequest> childSpec = (root, query, cb) -> root.get("mrPid").in(parentIds);
                List<MandateRequest> childRequests = mandateRequestRepository.findAll(childSpec);

                Set<Integer> seenIds = new HashSet<>(parentIds);
                for (MandateRequest child : childRequests) {
                    if (seenIds.add(child.getMrId())) {
                        requestsForCounts.add(child);
                    }
                }
            }

            Map<Integer, List<MandateRequest>> mrPIdAgainstList = requestsForCounts.stream().filter(mr -> mr.getMrPid() != null)
                    .collect(Collectors.groupingBy(MandateRequest::getMrPid));//xml records

            List<PhysicalCorporateFileDto> physicalCorporateDtos = new ArrayList<>();
            for (MandateRequest req : mandateRequestList) {
                PhysicalCorporateFileDto corporateDto = new PhysicalCorporateFileDto();
                if (mrPIdAgainstList.containsKey(req.getMrId())) {
                    corporateDto.setTspName(req.getTspLinkHdr().getTspName());
                    corporateDto.setEntityName(req.getCorporateEntityHdr() != null
                            ? req.getCorporateEntityHdr().getCeName()
                            : "");
                    corporateDto.setLastUpdatedAt(DateUtil.formatToReadableDate(req.getUpdatedAt()));

                    corporateDto.setTotalCount(mrPIdAgainstList.get(req.getMrId()).size());
                    corporateDto.setMrNachType((int) req.getMrNachType());
                    corporateDto.setSuccessCount((int) mrPIdAgainstList.get(req.getMrId()).stream().
                            filter(mrReq -> mrReq.getMrStatus() == 5).count());

                    corporateDto.setPendingCount((int) mrPIdAgainstList.get(req.getMrId()).stream()
                            .filter(txn -> txn.getMrStatus() == 1 // New Request
                                           || txn.getMrStatus() == 2 // Request Send to NPCI
                                           || txn.getMrStatus() == 3 // INP-ACK received
                                           || txn.getMrStatus() == 8 // Pending Checker Approval
                                           || txn.getMrStatus() == 9 // Approved by Checker
                                           || txn.getMrStatus() == 11 // Pending Checker-Reviewer Approval
                                           || txn.getMrStatus() == 12) // Approved by Checker-Reviewer
                            .count());

                    corporateDto.setRejectedCount((int) mrPIdAgainstList.get(req.getMrId()).stream()
                            .filter(mandateRequest -> mandateRequest.getMrStatus() == 4 // Rejected by NPCI
                                                      || mandateRequest.getMrStatus() == 6 // Rejected By Destination Bank
                                                      || mandateRequest.getMrStatus() == 7 // Rejected by SB
                                                      || mandateRequest.getMrStatus() == 10 // Rejected By Checker
                                                      || mandateRequest.getMrStatus() == 13) // Rejected By Checker-Reviewer
                            .count());

                    corporateDto.setFileName(mrIdAndMrFileName.get(req.getMrId()));
                    corporateDto.setMrpId(req.getMrId());
                    physicalCorporateDtos.add(corporateDto);
                } else {
                    if (req.getMrPid() == null) {
                        corporateDto.setTspName(req.getTspLinkHdr().getTspName());
                        corporateDto.setEntityName(req.getCorporateEntityHdr() != null
                                ? req.getCorporateEntityHdr().getCeName()
                                : "");
                        corporateDto.setLastUpdatedAt(DateUtil.formatToReadableDate(req.getUpdatedAt()));

                        corporateDto.setTotalCount(Stream.of(req).toList().size());
                        corporateDto.setMrNachType((int) req.getMrNachType());
                        corporateDto.setSuccessCount((int) Stream.of(req).
                                filter(mrReq -> mrReq.getMrStatus() == 5).count());

                        corporateDto.setPendingCount((int) Stream.of(req).filter(mandateRequest -> mandateRequest.getMrStatus() == 1 // New Request
                                                                                                   || mandateRequest.getMrStatus() == 2 // Request Send to NPCI0
                                                                                                   || mandateRequest.getMrStatus() == 3 // INP-ACK received
                                                                                                   || mandateRequest.getMrStatus() == 8 // Pending Checker Approval
                                                                                                   || mandateRequest.getMrStatus() == 9 // Approved by Checker
                                                                                                   || mandateRequest.getMrStatus() == 11 // Pending Checker-Reviewer Approval
                                                                                                   || mandateRequest.getMrStatus() == 12) // Approved by Checker-Reviewer
                                .count());

                        corporateDto.setRejectedCount((int) Stream.of(req).
                                filter(mandateRequest -> mandateRequest.getMrStatus() == 4 // Rejected by NPCI
                                                         || mandateRequest.getMrStatus() == 6 // Rejected By Destination Bank
                                                         || mandateRequest.getMrStatus() == 7 // Rejected by SB
                                                         || mandateRequest.getMrStatus() == 10 // Rejected By Checker
                                                         || mandateRequest.getMrStatus() == 13) // Rejected By Checker-Reviewer
                                .count());

                        corporateDto.setFileName(req.getMrFilename());
                        physicalCorporateDtos.add(corporateDto);
                    }
                }
            }
            loggerService.info("Returning corporate file", "getCorporateFileList", "");
            return new PageImpl<>(physicalCorporateDtos, pageRequest, mandateRequests.getTotalElements());
        } catch (Exception e) {
            loggerService.error("Error fetching corporate file", "getCorporateFileList", e.getMessage());
            throw new ServiceException(e.getMessage());
        }
    }
}
