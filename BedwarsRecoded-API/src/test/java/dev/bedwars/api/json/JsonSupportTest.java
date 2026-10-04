package dev.bedwars.api.json;

import dev.bedwars.api.dto.PartyInfo;
import dev.bedwars.api.dto.QueueRequest;
import dev.bedwars.api.dto.TemplateDescriptor;
import dev.bedwars.api.dto.TemplateSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire format depends on Optional and UUID being handled correctly. Default
 * Gson gets both wrong, so this locks in the behaviour every component relies on.
 */
class JsonSupportTest {

    @Test
    void optionalPresentSerialisesToBareValue() {
        String json = JsonSupport.gson().toJson(Optional.of("solo"));
        assertThat(json).isEqualTo("\"solo\"");
    }

    @Test
    void optionalEmptySerialisesToNull() {
        assertThat(JsonSupport.gson().toJson(Optional.empty())).isEqualTo("null");
    }

    @Test
    void uuidSerialisesAsString() {
        UUID id = UUID.fromString("12345678-1234-1234-1234-1234567890ab");
        assertThat(JsonSupport.gson().toJson(id)).isEqualTo("\"12345678-1234-1234-1234-1234567890ab\"");
    }

    @Test
    void queueRequestRoundTripsWithOptionalAndParty() {
        UUID leader = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        PartyInfo party = new PartyInfo("party-1", leader, List.of(leader, member));
        QueueRequest request = new QueueRequest(leader, "leader", 5, Optional.of("doubles"),
                Optional.of(party), 1234L);

        String json = JsonSupport.gson().toJson(request);
        QueueRequest decoded = JsonSupport.gson().fromJson(json, QueueRequest.class);

        assertThat(decoded).isEqualTo(request);
    }

    @Test
    void templateDescriptorRoundTripsWithEmptyChecksum() {
        TemplateDescriptor descriptor = new TemplateDescriptor("Glacier", "1.0.0", TemplateSource.S3, Optional.empty());
        String json = JsonSupport.gson().toJson(descriptor);
        TemplateDescriptor decoded = JsonSupport.gson().fromJson(json, TemplateDescriptor.class);
        assertThat(decoded).isEqualTo(descriptor);
        assertThat(decoded.checksum()).isEmpty();
    }
}