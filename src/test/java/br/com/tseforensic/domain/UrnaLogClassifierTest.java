package br.com.tseforensic.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UrnaLogClassifierTest {

    private static String event(String time, String message) {
        return "25/10/2026 " + time + "\tINFO\t1234\tVOTA\t" + message + "\tHASH";
    }

    @Test
    void classifiesVotesOnlyWhenActuallyComputed() {
        UrnaLogClassifier c = new UrnaLogClassifier(2);
        c.accept(event("08:00:01", "Aguardando digitação do identificador do eleitor"));
        c.accept(event("08:00:04", "Tipo de habilitação do eleitor [biométrica]"));
        c.accept(event("08:01:10", "O voto do eleitor foi computado"));

        c.accept(event("08:05:00", "Aguardando digitação do identificador do eleitor"));
        c.accept(event("08:05:12", "Solicitação de dado pessoal do eleitor para habilitação manual"));
        c.accept(event("08:05:30", "Ano de nascimento digitado é igual ao do cadastro"));
        c.accept(event("08:05:44", "O voto do eleitor foi computado"));

        c.accept(event("08:06:00", "Aguardando digitação do identificador do eleitor"));
        c.accept(event("08:06:30", "Ano de nascimento digitado é igual ao do cadastro"));
        c.accept(event("08:06:52", "O voto do eleitor foi computado"));

        c.accept(event("17:11:30", "O voto do eleitor foi computado"));
        c.accept(event("17:11:59", "Eleitor foi habilitado")); // no vote
        c.accept("04/10/2026 12:00:00\tINFO\t1234\tVOTA\tO voto do eleitor foi computado\tHASH");
        c.accept("25/10/2026 12:00:00\tINFO\t1234\tINITJE\tO voto do eleitor foi computado\tHASH");

        var result = c.summary();
        assertThat(result.counted()).isEqualTo(4);
        assertThat(result.recognized()).isEqualTo(1);
        assertThat(result.manualFailed()).isEqualTo(1);
        assertThat(result.noEnrollment()).isEqualTo(1);
        assertThat(result.unknown()).isEqualTo(1);
        assertThat(result.after17Local()).isEqualTo(1);
    }

    @Test
    void aggregatingDifferentLogsDoesNotEraseContingencyCount() {
        UrnaLogClassifier.Summary a = new UrnaLogClassifier.Summary(10,8,1,1,0,0,1);
        UrnaLogClassifier.Summary b = new UrnaLogClassifier.Summary(5,4,1,0,0,0,1);
        assertThat(a.plus(b).files()).isEqualTo(2);
        assertThat(a.plus(b).counted()).isEqualTo(15);
    }
}
