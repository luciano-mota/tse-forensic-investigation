package br.com.tseforensic.domain;

public record VoteAggregate(
        int electionRound,
        String uf,
        String municipalityCode,
        String municipality,
        int candidateNumber,
        String candidateName,
        long votes
) {
    public VoteAggregate withAdditionalVotes(long quantity) {
        return new VoteAggregate(electionRound, uf, municipalityCode,
                municipality, candidateNumber, candidateName, Math.addExact(votes, quantity));
    }
}
