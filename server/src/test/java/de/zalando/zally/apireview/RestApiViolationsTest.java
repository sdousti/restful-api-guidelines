package de.zalando.zally.apireview;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableMap;
import de.zalando.zally.dto.ApiDefinitionRequest;
import de.zalando.zally.dto.ApiDefinitionResponse;
import de.zalando.zally.dto.ViolationDTO;
import de.zalando.zally.exception.MissingApiDefinitionException;
import de.zalando.zally.rule.InvalidApiSchemaRule;
import de.zalando.zally.util.ErrorResponse;
import net.jadler.stubbing.server.jdk.JdkStubHttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.boot.actuate.autoconfigure.LocalManagementPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static de.zalando.zally.util.ResourceUtil.readApiDefinition;
import static de.zalando.zally.util.ResourceUtil.resourceToString;
import static net.jadler.Jadler.closeJadler;
import static net.jadler.Jadler.initJadlerUsing;
import static net.jadler.Jadler.onRequest;
import static net.jadler.Jadler.port;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.TEXT_PLAIN_VALUE;

@TestPropertySource(properties = "zally.message=" + RestApiViolationsTest.TEST_MESSAGE)
public class RestApiViolationsTest extends RestApiBaseTest {

    public static final String TEST_MESSAGE = "Test message";

    @LocalManagementPort
    private int managementPort;

    @Before
    public void setUp() {
        initJadlerUsing(new JdkStubHttpServer());
    }

    @After
    public void tearDown() {
        closeJadler();
    }

    @Test
    public void shouldValidateGivenApiDefinition() throws IOException {
        ApiDefinitionResponse response = sendApiDefinition(readApiDefinition("fixtures/api_spp.json"));

        List<ViolationDTO> violations = response.getViolations();
        assertThat(violations).isNotEmpty();
        assertThat(violations).hasSize(2);
        assertThat(violations.get(0).getTitle()).isEqualTo("dummy1");
        assertThat(violations.get(1).getTitle()).isEqualTo("dummy2");

        assertThat(response.getMessage()).isEqualTo(TEST_MESSAGE);
    }

    @Test
    public void shouldReturnCounters() throws IOException {
        ApiDefinitionResponse response = sendApiDefinition(readApiDefinition("fixtures/api_spp.json"));

        Map<String, Integer> count = response.getViolationsCount();
        assertThat(count.get("must")).isEqualTo(1);
        assertThat(count.get("should")).isEqualTo(0);
        assertThat(count.get("may")).isEqualTo(0);
        assertThat(count.get("hint")).isEqualTo(1);
    }

    @Test
    public void shouldReturnMetricsOfFoundViolations() throws IOException {
        sendApiDefinition(readApiDefinition("fixtures/api_spp.json"));

        ResponseEntity<JsonNode> metricsResponse = restTemplate.getForEntity("http://localhost:" + managementPort + "/metrics", JsonNode.class);
        assertThat(metricsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode rootObject = metricsResponse.getBody();
        assertThat(rootObject.has("meter.api-reviews.requested.fifteenMinuteRate")).isTrue();
        assertThat(rootObject.has("meter.api-reviews.processed.fifteenMinuteRate")).isTrue();
    }

    @Test
    public void shouldRespondWithBadRequestOnMalformedJson() throws IOException {
        ResponseEntity<ErrorResponse> responseEntity = sendApiDefinition(
                ApiDefinitionRequest.Factory.fromJson("{\"malformed\": \"dummy\""),
                ErrorResponse.class
        );

        assertThat(responseEntity.getStatusCode()).isEqualTo(BAD_REQUEST);
        assertThat(responseEntity.getHeaders().getContentType().toString()).isEqualTo(APPLICATION_PROBLEM_JSON);
        assertThat(responseEntity.getBody().getTitle()).isEqualTo(BAD_REQUEST.getReasonPhrase());
        assertThat(responseEntity.getBody().getStatus()).isNotEmpty();
        assertThat(responseEntity.getBody().getDetail()).isNotEmpty();
    }

    @Test
    public void shouldRespondWithBadRequestWhenApiDefinitionFieldIsMissing() throws IOException {
        ResponseEntity<ErrorResponse> responseEntity = restTemplate.postForEntity(
                API_VIOLATIONS_URL, ImmutableMap.of("my_api", "dummy"), ErrorResponse.class
        );

        assertThat(responseEntity.getStatusCode()).isEqualTo(BAD_REQUEST);
        assertThat(responseEntity.getHeaders().getContentType().toString()).isEqualTo(APPLICATION_PROBLEM_JSON);
        assertThat(responseEntity.getBody().getTitle()).isEqualTo(BAD_REQUEST.getReasonPhrase());
        assertThat(responseEntity.getBody().getStatus()).isNotEmpty();
        assertThat(responseEntity.getBody().getDetail()).isEqualTo(MissingApiDefinitionException.MESSAGE);
    }

    @Test
    public void shouldRespondWithViolationWhenApiDefinitionFieldIsNotValidSwaggerDefinition() throws IOException {
        ApiDefinitionResponse response = sendApiDefinition(
                ApiDefinitionRequest.Factory.fromJson("\"no swagger definition\"")
        );

        assertThat(response.getViolations()).hasSize(1);
        assertThat(response.getViolations().get(0).getTitle()).isEqualTo(new InvalidApiSchemaRule().getTitle());
    }

    @Test
    public void shouldReadJsonSpecificationFromUrl() throws Exception {
        String definitionUrl = getLocalUrl("fixtures/api_spp.json");

        List<ViolationDTO> violations = sendApiDefinition(
                ApiDefinitionRequest.Factory.fromUrl(definitionUrl)
        ).getViolations();

        assertThat(violations).hasSize(2);
        assertThat(violations.get(0).getTitle()).isEqualTo("dummy1");
        assertThat(violations.get(1).getTitle()).isEqualTo("dummy2");
    }

    @Test
    public void shouldReadYamlSpecificationFromUrl() throws Exception {
        String definitionUrl = getLocalUrl("fixtures/api_spa.yaml");

        List<ViolationDTO> violations = sendApiDefinition(
                ApiDefinitionRequest.Factory.fromUrl(definitionUrl)
        ).getViolations();

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).getTitle()).isEqualTo("dummy2");
    }

    @Test
    public void shouldReturn404WhenHostNotRecognised() throws Exception {
        ApiDefinitionRequest request = ApiDefinitionRequest.Factory.fromUrl("http://remote-localhost/test.yaml");
        ResponseEntity<ErrorResponse> responseEntity = restTemplate.postForEntity(
                API_VIOLATIONS_URL, request, ErrorResponse.class
        );

        assertThat(responseEntity.getStatusCode()).isEqualTo(NOT_FOUND);
        assertThat(responseEntity.getBody().getDetail()).isEqualTo("Unknown host: remote-localhost");
    }

    @Test
    public void shouldReturn404WhenNotFound() {
        ApiDefinitionRequest request = ApiDefinitionRequest.Factory.fromUrl(simulateNotFound());
        ResponseEntity<ErrorResponse> responseEntity = restTemplate.postForEntity(
                API_VIOLATIONS_URL, request, ErrorResponse.class
        );

        assertThat(responseEntity.getStatusCode()).isEqualTo(NOT_FOUND);
        assertThat(responseEntity.getBody().getDetail()).isEqualTo("404 Not Found");
    }

    @Test
    public void shouldStoreSuccessfulApiReviewRequest() throws IOException {
        sendApiDefinition(readApiDefinition("fixtures/api_spp.json"));
        assertThat(apiReviewRepository.count()).isEqualTo(1L);
        assertThat(apiReviewRepository.findAll().iterator().next().isSuccessfulProcessed()).isTrue();
    }

    @Test
    public void shouldStoreUnsuccessfulApiReviewRequest() {
        ApiDefinitionRequest request = ApiDefinitionRequest.Factory.fromUrl(simulateNotFound());
        restTemplate.postForEntity(API_VIOLATIONS_URL, request, ErrorResponse.class);
        assertThat(apiReviewRepository.count()).isEqualTo(1L);
        assertThat(apiReviewRepository.findAll().iterator().next().isSuccessfulProcessed()).isFalse();
    }

    private String getLocalUrl(String resourceName) throws Exception {
        String url = String.format("http://localhost:%d/%s", port(), resourceName);

        onRequest()
            .havingMethodEqualTo(GET.name())
            .havingPathEqualTo("/" + resourceName)
            .respond()
            .withStatus(OK.value())
            .withHeader(CONTENT_TYPE, APPLICATION_JSON_VALUE)
            .withBody(resourceToString(resourceName));

        return url;
    }

    private String simulateNotFound() {
        String remotePath = "/abcde.yaml";
        String url = "http://localhost:" + port() + remotePath;

        onRequest()
            .havingMethodEqualTo(GET.name())
            .havingPathEqualTo(remotePath)
            .respond()
            .withStatus(NOT_FOUND.value())
            .withHeader(CONTENT_TYPE, TEXT_PLAIN_VALUE)
            .withBody("NotFound");

        return url;
    }
}
