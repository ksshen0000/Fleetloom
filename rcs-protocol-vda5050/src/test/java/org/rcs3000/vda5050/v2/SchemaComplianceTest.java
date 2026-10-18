package org.rcs3000.vda5050.v2;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import java.util.stream.Stream;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Every message this library produces must validate against the official VDA 5050 2.1.0 JSON schemas. */
class SchemaComplianceTest {

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Vda5050Codec codec = new Vda5050Codec();

    static Stream<Arguments> samples() {
        return Stream.of(
                Arguments.of("order", SampleMessages.order()),
                Arguments.of("instantActions", SampleMessages.instantActions()),
                Arguments.of("state", SampleMessages.state()),
                Arguments.of("visualization", SampleMessages.visualization()),
                Arguments.of("connection", SampleMessages.connection()),
                Arguments.of("factsheet", SampleMessages.factsheet()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    void sampleMessageMatchesOfficialSchema(String schemaName, Vda5050Message message) {
        assertThat(errors(schemaName, codec.encode(message))).isEmpty();
    }

    @Test
    void minimalStateWithOnlyRequiredFieldsIsValid() {
        State minimal = State.of(null, "", 0, "", 0, false, null, null, null, OperatingMode.AUTOMATIC, null, null,
                null, null, null, null, new BatteryState(100, null, null, false, null), null, SafetyState.CLEAR)
                .withHeader(SampleMessages.HEADER);

        assertThat(errors("state", codec.encode(minimal))).isEmpty();
    }

    @Test
    void validatorRejectsAMessageMissingARequiredField() {
        ObjectNode order = (ObjectNode) JSON.readTree(codec.encode(SampleMessages.order()));
        order.remove("nodes");

        assertThat(errors("order", JSON.writeValueAsBytes(order)))
                .anySatisfy(e -> assertThat(e.getMessage()).contains("nodes"));
    }

    @Test
    void factsheetSchemaDefectIsStillPresentUpstream() {
        // If this fails, the bundled schema no longer has the defect and patchKnownDefects can go.
        JsonNode blockingTypes = loadSchema("factsheet").at(BLOCKING_TYPES);
        assertThat(blockingTypes.path("type").asString()).isEqualTo("array");
        assertThat(blockingTypes.has("enum")).isTrue();
    }

    private static final String BLOCKING_TYPES =
            "/properties/protocolFeatures/properties/agvActions/items/properties/blockingTypes";

    private static List<Error> errors(String schemaName, byte[] json) {
        Schema schema = REGISTRY.getSchema(patchKnownDefects(schemaName, loadSchema(schemaName)));
        return schema.validate(JSON.readTree(json));
    }

    private static JsonNode loadSchema(String schemaName) {
        String path = "/vda5050/2.1.0/schemas/" + schemaName + ".schema";
        try (InputStream in = SchemaComplianceTest.class.getResourceAsStream(path)) {
            return JSON.readTree(in);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The 2.1.0 factsheet schema declares {@code blockingTypes} as an array but puts the string enum
     * on the array itself, so no array can satisfy it. Move the enum to the items, as the text intends.
     */
    private static JsonNode patchKnownDefects(String schemaName, JsonNode schema) {
        if (schemaName.equals("factsheet")) {
            ObjectNode blockingTypes = (ObjectNode) schema.at(BLOCKING_TYPES);
            JsonNode values = blockingTypes.remove("enum");
            blockingTypes.putObject("items").put("type", "string").set("enum", values);
        }
        return schema;
    }
}
