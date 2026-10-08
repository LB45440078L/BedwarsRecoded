package dev.bedwars.core.config;

import dev.bedwars.core.domain.ArenaGroup;
import dev.bedwars.core.domain.GeneratorTier;
import dev.bedwars.core.domain.GeneratorType;
import dev.bedwars.core.domain.Vec3;
import dev.bedwars.core.shop.Currency;
import dev.bedwars.core.shop.Price;
import dev.bedwars.core.shop.Shop;
import dev.bedwars.core.shop.ShopCategory;
import dev.bedwars.core.shop.ShopItem;
import dev.bedwars.core.upgrade.UpgradeCatalog;
import dev.bedwars.core.upgrade.UpgradeTier;
import dev.bedwars.core.upgrade.UpgradeType;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads an {@link ArenaDefinition} from YAML. Deliberately uses SnakeYAML (a plain
 * library) so Core stays free of Bukkit's configuration classes and remains
 * unit-testable.
 */
public final class ArenaConfigLoader {

    public ArenaDefinition load(InputStream yamlStream) {
        Map<String, Object> root = new Yaml().load(yamlStream);
        if (root == null) {
            throw new IllegalArgumentException("Empty arena config");
        }
        ArenaGroup group = parseGroup(map(root.get("group")));

        Map<String, Vec3> beds = new LinkedHashMap<>();
        Map<String, Vec3> spawns = new LinkedHashMap<>();
        for (Object teamObj : list(root.get("teams"))) {
            Map<String, Object> team = map(teamObj);
            String id = str(team.get("id"));
            beds.put(id, vec(team.get("bed")));
            spawns.put(id, vec(team.get("spawn")));
        }

        List<GeneratorSpec> generators = new ArrayList<>();
        for (Object genObj : list(root.get("generators"))) {
            Map<String, Object> gen = map(genObj);
            generators.add(new GeneratorSpec(
                    str(gen.get("id")),
                    GeneratorType.valueOf(str(gen.get("type")).toUpperCase()),
                    GeneratorTier.valueOf(str(gen.get("tier")).toUpperCase()),
                    vec(gen)));
        }

        Shop shop = parseShop(map(root.get("shop")));

        List<String> startItems = new ArrayList<>();
        for (Object item : list(root.getOrDefault("start-items", List.of()))) {
            startItems.add(str(item));
        }
        UpgradeCatalog upgrades = parseUpgrades(map(root.getOrDefault("upgrades", Map.of())));
        return new ArenaDefinition(group, beds, spawns, generators, shop, startItems, upgrades);
    }

    /** Parses the per-group upgrade trees; falls back to the built-in defaults. */
    private UpgradeCatalog parseUpgrades(Map<String, Object> upgrades) {
        Map<UpgradeType, List<UpgradeTier>> byType = new EnumMap<>(UpgradeType.class);
        for (Map.Entry<String, Object> entry : upgrades.entrySet()) {
            UpgradeType type;
            try {
                type = UpgradeType.valueOf(entry.getKey().toUpperCase());
            } catch (IllegalArgumentException ex) {
                continue; // ignore unknown upgrade names rather than failing the whole config
            }
            List<UpgradeTier> tiers = new ArrayList<>();
            for (Object tierObj : list(entry.getValue())) {
                Map<String, Object> tier = map(tierObj);
                tiers.add(new UpgradeTier(
                        type,
                        intVal(tier.get("level")),
                        new Price(Currency.valueOf(str(tier.get("currency")).toUpperCase()), intVal(tier.get("amount"))),
                        doubleVal(tier.getOrDefault("effect", 0)),
                        str(tier.getOrDefault("description", type.displayName()))));
            }
            byType.put(type, tiers);
        }
        return byType.isEmpty() ? UpgradeCatalog.defaults() : new UpgradeCatalog(byType);
    }

    /**
     * The waiting room, under any of the names this project has used for it:
     * {@code lobby-spawn} / {@code waiting-room} here, and {@code map-lobby-spawn} as the
     * original Bedwars plugin spelled it -- an operator porting their Glacier config should
     * not have to rename the key.
     */
    private Optional<Vec3> parseWaitingRoom(Map<String, Object> g) {
        for (String key : List.of("lobby-spawn", "waiting-room", "map-lobby-spawn")) {
            Object value = g.get(key);
            if (value instanceof Map<?, ?> coordinates
                    && coordinates.containsKey("x") && coordinates.containsKey("y")
                    && coordinates.containsKey("z")) {
                return Optional.of(vec(value));
            }
        }
        return Optional.empty();
    }

    private ArenaGroup parseGroup(Map<String, Object> g) {
        List<GeneratorType> teamGenerators = new ArrayList<>();
        for (Object t : list(g.getOrDefault("team-generators", List.of("IRON", "GOLD")))) {
            teamGenerators.add(GeneratorType.valueOf(str(t).toUpperCase()));
        }
        return new ArenaGroup(
                str(g.getOrDefault("id", "solo")),
                intVal(g.getOrDefault("team-count", 2)),
                intVal(g.getOrDefault("players-per-team", 2)),
                intVal(g.getOrDefault("countdown-seconds", 15)),
                intVal(g.getOrDefault("sudden-death-after-seconds", 300)),
                doubleVal(g.getOrDefault("void-y-threshold", 0.0)),
                doubleVal(g.getOrDefault("island-radius", 30.0)),
                doubleVal(g.getOrDefault("bed-protection-radius", 3.0)),
                teamGenerators,
                parseWaitingRoom(g),
                intVal(g.getOrDefault("min-players", 0)),
                boolVal(g.getOrDefault("waiting-room-platform", true)));
    }

    private Shop parseShop(Map<String, Object> s) {
        List<ShopCategory> categories = new ArrayList<>();
        for (Object catObj : list(s.get("categories"))) {
            Map<String, Object> cat = map(catObj);
            List<ShopItem> items = new ArrayList<>();
            for (Object itemObj : list(cat.get("items"))) {
                items.add(parseItem(str(cat.get("id")), map(itemObj)));
            }
            categories.add(new ShopCategory(
                    str(cat.get("id")),
                    str(cat.get("display-name")),
                    intVal(cat.getOrDefault("slot", 0)),
                    str(cat.getOrDefault("icon", "STONE")),
                    items));
        }
        return new Shop(str(s.getOrDefault("id", "default")), str(s.getOrDefault("display-name", "Shop")), categories);
    }

    private ShopItem parseItem(String categoryId, Map<String, Object> item) {
        Price price = new Price(
                Currency.valueOf(str(item.get("currency")).toUpperCase()),
                intVal(item.get("amount")));
        List<String> enchants = new ArrayList<>();
        for (Object e : list(item.getOrDefault("enchants", List.of()))) {
            enchants.add(str(e));
        }
        Optional<String> group = item.containsKey("upgrade-group")
                ? Optional.of(str(item.get("upgrade-group"))) : Optional.empty();
        return new ShopItem(
                str(item.get("id")),
                categoryId,
                str(item.get("display-name")),
                intVal(item.getOrDefault("slot", 0)),
                str(item.getOrDefault("material", "STONE")),
                intVal(item.getOrDefault("give-amount", 1)),
                price,
                enchants,
                boolVal(item.getOrDefault("permanent", false)),
                boolVal(item.getOrDefault("downgradable", false)),
                group,
                intVal(item.getOrDefault("tier", 0)));
    }

    // ---- tiny typed accessors -------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List<?> l ? (List<Object>) l : List.of();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int intVal(Object value) {
        return value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private static double doubleVal(Object value) {
        return value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
    }

    private static boolean boolVal(Object value) {
        return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }

    private static Vec3 vec(Object value) {
        Map<String, Object> m = map(value);
        return new Vec3(doubleVal(m.get("x")), doubleVal(m.get("y")), doubleVal(m.get("z")));
    }
}