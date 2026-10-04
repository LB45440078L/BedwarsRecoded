package dev.bedwars.api.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.UUID;

/**
 * The canonical Gson instance for the wire protocol. Handles {@link java.util.Optional}
 * and {@link UUID} (as a plain string), so every component serialises DTOs identically.
 */
public final class JsonSupport {

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapterFactory(new OptionalTypeAdapterFactory())
            .registerTypeAdapter(UUID.class, new UuidAdapter())
            .create();

    private JsonSupport() {
    }

    public static Gson gson() {
        return GSON;
    }

    private static final class UuidAdapter extends TypeAdapter<UUID> {
        @Override
        public void write(JsonWriter out, UUID value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }

        @Override
        public UUID read(JsonReader in) throws IOException {
            if (in.peek() == com.google.gson.stream.JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return UUID.fromString(in.nextString());
        }
    }
}