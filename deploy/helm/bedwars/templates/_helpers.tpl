{{/* Common labels applied to every resource. */}}
{{- define "bedwars.labels" -}}
app.kubernetes.io/part-of: bedwars-recoded
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{/* Fully-qualified image reference for a given image name. */}}
{{- define "bedwars.image" -}}
{{- if .root.Values.image.registry -}}
{{ .root.Values.image.registry }}/{{ .name }}:{{ .root.Values.image.tag }}
{{- else -}}
{{ .name }}:{{ .root.Values.image.tag }}
{{- end -}}
{{- end -}}

{{/* Controller base URL, used by pods and proxies. */}}
{{- define "bedwars.controllerUrl" -}}
http://bedwars-controller:8080
{{- end -}}
