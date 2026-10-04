package dev.bedwars.controller.k8s;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GameServerSet spec. Unknown fields are round-tripped through the catch-all map
 * so scaling only touches {@code replicas}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GameServerSetSpec {

    private Integer replicas;
    private final Map<String, Object> additional = new LinkedHashMap<>();

    public Integer getReplicas() {
        return replicas;
    }

    public void setReplicas(Integer replicas) {
        this.replicas = replicas;
    }

    @JsonAnyGetter
    public Map<String, Object> getAdditionalProperties() {
        return additional;
    }

    @JsonAnySetter
    public void setAdditionalProperty(String name, Object value) {
        additional.put(name, value);
    }
}