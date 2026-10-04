package dev.bedwars.api.dto;

/** Where a pod sources its Slime template from. */
public enum TemplateSource {
    /** Production: object storage, loaded via AdvancedSlimePaper. */
    S3,
    /** Local/dev only. The "copy world on same server" fallback. */
    LOCAL
}