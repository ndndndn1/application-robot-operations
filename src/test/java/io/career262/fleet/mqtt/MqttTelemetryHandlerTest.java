package io.career262.fleet.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.career262.fleet.TelemetryController.*;
import io.career262.fleet.TelemetryService;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MqttTelemetryHandlerTest {
    static final ValidatorFactory VALIDATION = Validation.buildDefaultValidatorFactory();
    static final String ID = "ad37a1bf-047e-4167-bf94-0c5e6b88d547";
    final TelemetryService service = mock(TelemetryService.class);
    final MqttRejectionStore rejections = mock(MqttRejectionStore.class);
    final ObjectMapper mapper = new ObjectMapper();
    final MqttTelemetryHandler handler = new MqttTelemetryHandler(service, rejections,
            VALIDATION.getValidator(), mapper, new MqttSettings("tcp://localhost:1883","test",86400,16384));
    final Telemetry input = new Telemetry("r1", 1700000000.0, 0.0, 0.0, 80.0, "moving", List.of(), "standard", ID);
    @AfterAll static void closeValidator() { VALIDATION.close(); }
    byte[] body() throws Exception { return mapper.writeValueAsBytes(input); }
    void rejected(byte[] body, int qos, boolean retained, String reason) {
        assertThat(handler.handle("robots/r1/telemetry",body,qos,retained).outcome()).isEqualTo("rejected");
        verify(rejections).record(eq("robots/r1/telemetry"),eq(body),eq(qos),eq(retained),eq(reason),nullable(String.class));
        verifyNoInteractions(service);
    }
    @Test void originalAndDuplicateReuseTelemetryService() throws Exception {
        when(service.ingest(input)).thenReturn(new Response(ID,input,"normal",List.of(),null,null,false))
                .thenReturn(new Response(ID,input,"normal",List.of(),null,null,true));
        assertThat(handler.handle("robots/r1/telemetry",body(),1,false).outcome()).isEqualTo("accepted");
        assertThat(handler.handle("robots/r1/telemetry",body(),1,false).outcome()).isEqualTo("duplicate");
        verifyNoInteractions(rejections);
    }
    @Test void malformedUnknownFieldsAndTrailingValuesAreRejected() throws Exception {
        for (String json : List.of("{broken", "null", new String(body(),StandardCharsets.UTF_8).replace("\"robotId\"", "\"unknown\""),
                new String(body(),StandardCharsets.UTF_8) + " {}")) {
            rejected(json.getBytes(StandardCharsets.UTF_8),1,false,"invalid_json");
            clearInvocations(rejections);
        }
    }
    @Test void beanValidationChecksNestedErrorsAndRanges() throws Exception {
        for (Telemetry bad : List.of(
                new Telemetry("r1",1.0,0.0,0.0,101.0,"moving",List.of(),"standard",ID),
                new Telemetry("r1",1.0,0.0,0.0,80.0,"moving",List.of("lowercase"),"standard",ID),
                new Telemetry("r1",null,0.0,0.0,80.0,"moving",List.of(),"standard",ID))) {
            rejected(mapper.writeValueAsBytes(bad),1,false,"validation"); clearInvocations(rejections);
        }
    }
    @Test void eventIdIsRequiredEvenThoughHttpMayGenerateOne() throws Exception {
        rejected(mapper.writeValueAsBytes(new Telemetry("r1",1.0,0.0,0.0,80.0,"moving",List.of(),"standard",null)),
                1,false,"missing_event_id");
    }
    @Test void transportContractIsEnforced() throws Exception {
        rejected(new byte[16385],1,false,"oversized"); clearInvocations(rejections);
        rejected(body(),1,true,"retained"); clearInvocations(rejections);
        rejected(body(),0,false,"qos");
    }
    @Test void topicMustMatchRobot() throws Exception {
        handler.handle("robots/other/telemetry",body(),1,false);
        verify(rejections).record(eq("robots/other/telemetry"),any(),eq(1),eq(false),eq("topic_robot_mismatch"),eq(ID));
        verifyNoInteractions(service);
    }
    @Test void conflictIsDurablyRejectedButDbFailuresEscape() throws Exception {
        when(service.ingest(input)).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT));
        assertThat(handler.handle("robots/r1/telemetry",body(),1,false).outcome()).isEqualTo("rejected");
        verify(rejections).record(anyString(),any(),eq(1),eq(false),eq("conflict"),eq(ID));
        doThrow(new DataAccessResourceFailureException("offline")).when(service).ingest(input);
        assertThatThrownBy(() -> handler.handle("robots/r1/telemetry",body(),1,false))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
    @Test void failedRejectionCommitMustEscapeForRedelivery() {
        doThrow(new DataAccessResourceFailureException("offline")).when(rejections)
                .record(anyString(),any(),anyInt(),anyBoolean(),anyString(),nullable(String.class));
        assertThatThrownBy(() -> handler.handle("robots/r1/telemetry",new byte[16385],1,false))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
