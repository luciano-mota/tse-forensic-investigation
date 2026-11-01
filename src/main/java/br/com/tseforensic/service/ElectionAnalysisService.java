package br.com.tseforensic.service;

import br.com.tseforensic.domain.AuditMath;
import br.com.tseforensic.domain.CandidateResult;
import br.com.tseforensic.domain.RegionResult;
import br.com.tseforensic.infra.repository.ElectionRepository;
import br.com.tseforensic.infra.repository.ElectionRepository.VoteRow;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ElectionAnalysisService {

    private final ElectionRepository repository;

    public ElectionAnalysisService(ElectionRepository repository) {
        this.repository = repository;
    }

    public RegionResult overview(int round) {
        return summarize("BR", "Brasil", repository.votes(round, null));
    }

    public List<RegionResult> states(int round) {
        Map<String, List<VoteRow>> grouped = new LinkedHashMap<>();
        for (VoteRow row : repository.votes(round, null)) {
            grouped.computeIfAbsent(row.uf(), key -> new ArrayList<>()).add(row);
        }
        return grouped.entrySet().stream()
                .map(item -> summarize(item.getKey(), item.getKey(), item.getValue()))
                .sorted(Comparator.comparing(RegionResult::code))
                .toList();
    }

    public List<RegionResult> municipalities(int round, String uf) {
        Map<String, List<VoteRow>> grouped = new LinkedHashMap<>();
        for (VoteRow row : repository.votes(round, uf)) {
            grouped.computeIfAbsent(row.municipalityCode(), key -> new ArrayList<>()).add(row);
        }
        return grouped.entrySet().stream()
                .map(item -> summarize(item.getKey(), item.getValue().getFirst().municipality(), item.getValue()))
                .sorted(Comparator.comparing(RegionResult::name))
                .toList();
    }

    public List<Shift> changes(String uf, long minValidVotes, BigDecimal minAbsChangePp) {
        /// Use municipality+UF keys from repository directly to avoid duplicated TSE codes across UFs.
        Map<String, RegionalVotes> baseline = groupForComparison(repository.votes(1, uf));
        Map<String, RegionalVotes> current = groupForComparison(repository.votes(2, uf));
        List<Shift> differences = new ArrayList<>();
        for (Map.Entry<String, RegionalVotes> item : current.entrySet()) {
            RegionalVotes before = baseline.get(item.getKey());
            RegionalVotes after = item.getValue();
            if (before == null || before.total < minValidVotes || after.total < minValidVotes) {
                continue;
            }
            for (int candidate : new int[]{13, 22}) {
                long firstVotes = before.votes.getOrDefault(candidate, 0L);
                long secondVotes = after.votes.getOrDefault(candidate, 0L);
                BigDecimal change = AuditMath.percentagePointChange(
                        firstVotes, before.total, secondVotes, after.total);
                if (change.abs().compareTo(minAbsChangePp) >= 0) {
                    differences.add(new Shift(after.uf, after.code, after.name, candidate,
                            firstVotes, secondVotes, before.total, after.total, change,
                            "VARIACAO_DESCRITIVA"));
                }
            }
        }
        return differences.stream()
                .sorted(Comparator.comparing((Shift s) -> s.changePp().abs()).reversed()
                        .thenComparing(Shift::uf).thenComparing(Shift::municipalityCode))
                .limit(150)
                .toList();
    }

    private Map<String, RegionalVotes> groupForComparison(List<VoteRow> rows) {
        Map<String, RegionalVotes> map = new HashMap<>();
        for (VoteRow row : rows) {
            String key = row.uf() + ":" + row.municipalityCode();
            RegionalVotes region = map.computeIfAbsent(key,
                    unused -> new RegionalVotes(row.uf(), row.municipalityCode(), row.municipality()));
            region.votes.merge(row.candidateNumber(), row.votes(), Math::addExact);
            region.total = Math.addExact(region.total, row.votes());
        }
        return map;
    }

    private RegionResult summarize(String code, String name, List<VoteRow> rows) {
        Map<Integer, Long> totals = new HashMap<>();
        Map<Integer, String> names = new HashMap<>();
        for (VoteRow row : rows) {
            totals.merge(row.candidateNumber(), row.votes(), Math::addExact);
            names.putIfAbsent(row.candidateNumber(), row.candidateName());
        }
        long validVotes = totals.values().stream().mapToLong(Long::longValue).sum();
        List<CandidateResult> candidates = totals.entrySet().stream()
                .map(entry -> new CandidateResult(entry.getKey(), names.get(entry.getKey()),
                        entry.getValue(), AuditMath.percent(entry.getValue(), validVotes)))
                .sorted(Comparator.comparing(CandidateResult::votes).reversed())
                .toList();
        return new RegionResult(code, name, validVotes, candidates);
    }

    private static class RegionalVotes {
        final String uf;
        final String code;
        final String name;
        final Map<Integer, Long> votes = new HashMap<>();
        long total;

        RegionalVotes(String uf, String code, String name) {
            this.uf = uf;
            this.code = code;
            this.name = name;
        }
    }

    public record Shift(
            String uf,
            String municipalityCode,
            String municipality,
            int candidateNumber,
            long firstVotes,
            long secondVotes,
            long firstValidVotes,
            long secondValidVotes,
            BigDecimal changePp,
            String classification
    ) {
    }
}
