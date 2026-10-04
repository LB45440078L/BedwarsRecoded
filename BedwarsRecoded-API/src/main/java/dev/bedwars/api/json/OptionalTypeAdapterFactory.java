package dev.bedwars.api.json;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Optional;

/**
 * Gson adapter for {@link Optional}. Gson has no built-in support; without this,
 * {@code Optional.of(x)} serialises as {@code {"present":true}} instead of {@code x}.
 */
public final class OptionalTypeAdapterFactory implements TypeAdapterFactory {

    @Override
    @SuppressWarnings("unchecked")
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
        if (type.getRawType() != Optional.class) {
            return null;
        }
        Type elementType;
        if (type.getType() instanceof ParameterizedType parameterized) {
            elementType = parameterized.getActualTypeArguments()[0];
        } else {
            elementType = Object.class;
        }
        TypeAdapter<?> elementAdapter = gson.getAdapter(TypeToken.get(elementType));
        TypeAdapter<Optional<?>> adapter = new TypeAdapter<>() {
            @Override
            public void write(JsonWriter out, Optional<?> value) throws IOException {
                if (value == null || value.isEmpty()) {
                    out.nullValue();
                } else {
                    ((TypeAdapter<Object>) elementAdapter).write(out, value.get());
                }
            }

            @Override
            public Optional<?> read(JsonReader in) throws IOException {
                if (in.peek() == com.google.gson.stream.JsonToken.NULL) {
                    in.nextNull();
                    return Optional.empty();
                }
                try {
                    return Optional.ofNullable(elementAdapter.read(in));
                } catch (JsonSyntaxException e) {
                    throw new com.google.gson.JsonIOException(e);
                }
            }
        };
        return (TypeAdapter<T>) adapter;
    }
}