package com.voyager.sesmatcher.repository;

import com.voyager.sesmatcher.entity.MatchRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface MatchRecordRepository extends JpaRepository<MatchRecord, Long> {

    boolean existsByCandidateEmailIdAndProjectEmailId(String candidateEmailId, String projectEmailId);

    boolean existsByCandidateEmailIdAndCandidateFileNameAndProjectEmailId(
            String candidateEmailId,
            String candidateFileName,
            String projectEmailId
    );

    List<MatchRecord> findAllByOrderByReceivedAtDescCreatedAtDesc();

    List<MatchRecord> findAllByOrderByCreatedAtDesc();

    List<MatchRecord> findByFitRateGreaterThanEqualOrderByReceivedAtDescCreatedAtDesc(Integer minFitRate);

    List<MatchRecord> findByFitRateGreaterThanEqualOrderByCreatedAtDesc(Integer minFitRate);

    List<MatchRecord> findByCandidateFileNameOrderByReceivedAtDescCreatedAtDesc(String candidateFileName);

    List<MatchRecord> findByCandidateEmailIdAndCandidateFileNameOrderByReceivedAtDescCreatedAtDesc(
            String candidateEmailId,
            String candidateFileName
    );

    @Transactional
    void deleteByCandidateEmailId(String candidateEmailId);

    @Transactional
    void deleteByCandidateFileName(String candidateFileName);
}
