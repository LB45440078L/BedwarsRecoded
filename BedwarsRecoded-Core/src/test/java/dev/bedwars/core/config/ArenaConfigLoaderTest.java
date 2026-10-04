package dev.bedwars.core.config;

import dev.bedwars.core.domain.GeneratorType;
import dev.bedwars.core.shop.Currency;
import dev.bedwars.core.upgrade.UpgradeType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ArenaConfigLoaderTest {

    private static final String YAML = """
            group:
              id: solo
              team-count: 4
              players-per-team: 1
              countdown-seconds: 20
              sudden-death-after-seconds: 240
              void-y-threshold: -5.0
              island-radius: 25.0
              bed-protection-radius: 3.5
              team-generators: [IRON, GOLD]
            teams:
              - id: red
                bed: { x: 10, y: 64, z: 10 }
                spawn: { x: 10, y: 66, z: 10 }
              - id: blue
                bed: { x: -10, y: 64, z: -10 }
                spawn: { x: -10, y: 66, z: -10 }
            generators:
              - id: diamond-center
                type: DIAMOND
                tier: I
                x: 0
                y: 64
                z: 0
            shop:
              id: default
              display-name: "Item Shop"
              categories:
                - id: blocks
                  display-name: "Blocks"
                  slot: 0
                  icon: WHITE_WOOL
                  items:
                    - id: wool
                      display-name: "Wool"
                      slot: 0
                      material: WHITE_WOOL
                      give-amount: 16
                      currency: IRON
                      amount: 4
            """;

    @Test
    void loadsGroupTeamsGeneratorsAndShop() {
        ArenaDefinition definition = new ArenaConfigLoader()
                .load(new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8)));

        assertThat(definition.group().id()).isEqualTo("solo");
        assertThat(definition.group().teamCount()).isEqualTo(4);
        assertThat(definition.group().voidYThreshold()).isEqualTo(-5.0);
        assertThat(definition.teamBeds()).containsKeys("red", "blue");
        assertThat(definition.bedOf("red")).isPresent();
        assertThat(definition.generators()).hasSize(1);
        assertThat(definition.generators().getFirst().type()).isEqualTo(GeneratorType.DIAMOND);
        assertThat(definition.shop().item("wool")).isPresent();
        assertThat(definition.shop().item("wool").orElseThrow().price().currency()).isEqualTo(Currency.IRON);
        assertThat(definition.shop().item("wool").orElseThrow().price().amount()).isEqualTo(4);
    }

    private static final String UPGRADE_YAML = """
            group:
              id: solo
              team-count: 2
              players-per-team: 1
            teams:
              - id: red
                bed: { x: 1, y: 64, z: 1 }
                spawn: { x: 1, y: 66, z: 1 }
            shop:
              id: default
              display-name: "Item Shop"
              categories: []
            upgrades:
              SHARPNESS:
                - level: 1
                  currency: DIAMOND
                  amount: 2
                  effect: 1
                  description: "Sharpness I"
                - level: 2
                  currency: DIAMOND
                  amount: 6
                  effect: 2
                  description: "Sharpness II"
            """;

    @Test
    void loadsPerGroupUpgradeTreeFromYaml() {
        ArenaDefinition definition = new ArenaConfigLoader()
                .load(new ByteArrayInputStream(UPGRADE_YAML.getBytes(StandardCharsets.UTF_8)));

        var tiers = definition.upgrades().tiers(UpgradeType.SHARPNESS);
        assertThat(tiers).hasSize(2);
        assertThat(tiers.getFirst().level()).isEqualTo(1);
        assertThat(tiers.getFirst().price().currency()).isEqualTo(Currency.DIAMOND);
        assertThat(tiers.getFirst().price().amount()).isEqualTo(2);
        assertThat(tiers.getFirst().description()).isEqualTo("Sharpness I");
        // A type with no YAML entry is simply absent, not defaulted.
        assertThat(definition.upgrades().tiers(UpgradeType.PROTECTION)).isEmpty();
    }

    @Test
    void fallsBackToDefaultUpgradesWhenSectionAbsent() {
        ArenaDefinition definition = new ArenaConfigLoader()
                .load(new ByteArrayInputStream(YAML.getBytes(StandardCharsets.UTF_8)));

        assertThat(definition.upgrades().tiers(UpgradeType.SHARPNESS)).isNotEmpty();
        assertThat(definition.upgrades().tiers(UpgradeType.PROTECTION)).hasSize(4);
    }
}