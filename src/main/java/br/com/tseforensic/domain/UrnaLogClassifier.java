package br.com.tseforensic.domain;

import java.util.Locale;

/**
 * Classifies aggregate voting-session events without storing identifiers or log text.
 * A log event is not a vote identity. It cannot reveal a candidate choice.
 */
public final class UrnaLogClassifier {

    private final String electionDate;
    private Type lastType = Type.UNKNOWN;
    private long counted;
    private long recognized;
    private long manualFailed;
    private long noEnrollment;
    private long unknown;
    private long after17Local;

    private enum Type { UNKNOWN, BIOMETRIC, MANUAL_FAILED, NO_ENROLLMENT }

    public UrnaLogClassifier(int round) {
        if (round != 1 && round != 2) throw new IllegalArgumentException("Turno inválido");
        electionDate = round == 1 ? "04/10/2026" : "25/10/2026";
    }

    public void accept(String rawLine) {
        if (rawLine == null || !rawLine.startsWith(electionDate)) return;
        String[] columns = rawLine.split("\t", 6);
        if (columns.length < 5 || !"VOTA".equalsIgnoreCase(columns[3].trim())) return;
        String message = columns[4].toLowerCase(Locale.ROOT);

        if (message.contains("aguardando digitação do identificador do eleitor")
                || message.contains("aguardando digitação do título")) {
            lastType = Type.UNKNOWN;
        } else if (message.contains("tipo de habilitação do eleitor [biométrica]")) {
            lastType = Type.BIOMETRIC;
        } else if (message.contains("solicitação de dado pessoal do eleitor para habilitação manual")) {
            lastType = Type.MANUAL_FAILED;
        } else if (message.contains("o eleitor não possui biometria")
                || (message.contains("ano de nascimento digitado é igual ao do cadastro")
                && lastType == Type.UNKNOWN)) {
            lastType = Type.NO_ENROLLMENT;
        } else if (message.contains("o voto do eleitor foi computado")) {
            counted++;
            switch (lastType) {
                case BIOMETRIC -> recognized++;
                case MANUAL_FAILED -> manualFailed++;
                case NO_ENROLLMENT -> noEnrollment++;
                case UNKNOWN -> unknown++;
            }
            // Local clock, not UTC or Brasília time; do not misrepresent as late voting.
            if (rawLine.length() >= 13) {
                try {
                    int hour = Integer.parseInt(rawLine.substring(11, 13));
                    if (hour >= 17) after17Local++;
                } catch (NumberFormatException ignored) {
                    // A malformed timestamp is not a time of voting.
                }
            }
            lastType = Type.UNKNOWN;
        }
    }

    public Summary summary() {
        return new Summary(counted, recognized, manualFailed, noEnrollment,
                unknown, after17Local, 1);
    }

    public record Summary(long counted, long recognized, long manualFailed,
                          long noEnrollment, long unknown, long after17Local, long files) {
        public Summary plus(Summary other) {
            return new Summary(
                    Math.addExact(counted, other.counted),
                    Math.addExact(recognized, other.recognized),
                    Math.addExact(manualFailed, other.manualFailed),
                    Math.addExact(noEnrollment, other.noEnrollment),
                    Math.addExact(unknown, other.unknown),
                    Math.addExact(after17Local, other.after17Local),
                    Math.addExact(files, other.files));
        }
    }
}
