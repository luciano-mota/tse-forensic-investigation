package br.com.tseforensic.domain;

import java.util.List;

public record RegionResult(
        String code,
        String name,
        long validVotes,
        List<CandidateResult> candidates
) {
}
