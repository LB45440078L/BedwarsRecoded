package dev.bedwars.controller.k8s;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/** GameServerSet status. Treated as an opaque, round-tripped map. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GameServerSetStatus {

    private Integer replicas;
    private Integer readyReplicas;
    private final Map<String, Object> additional = new LinkedHashMap<>();

    public Integer getReplicas() {
        return replicas;
    }

    public void setReplicas(Integer replicas) {
        this.replicas = replicas;
    }

    public Integer getReadyReplicas() {
        return readyReplicas;
    }

    public void setReadyReplicas(Integer readyReplicas) {
        this.readyReplicas = readyReplicas;
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