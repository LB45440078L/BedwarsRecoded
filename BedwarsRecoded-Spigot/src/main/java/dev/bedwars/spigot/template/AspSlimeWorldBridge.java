package dev.bedwars.spigot.template;

import com.infernalsuite.aswm.api.AdvancedSlimePaperAPI;
import com.infernalsuite.aswm.api.loaders.SlimeLoader;
import com.infernalsuite.aswm.api.world.SlimeWorld;
import com.infernalsuite.aswm.api.world.properties.SlimePropertyMap;

/**
 * The real AdvancedSlimePaper implementation of the loader bridge. Deliberately
 * thin: it only translates the bridge calls into ASP API calls.
 */
public final class AspSlimeWorldBridge implements SlimeWorldBridge {

    private final AdvancedSlimePaperAPI asp;
    private final SlimeLoader loader;
    private final String dataSource;
    private final SlimePropertyMap properties;

    public AspSlimeWorldBridge(AdvancedSlimePaperAPI asp, SlimeLoader loader, String dataSource,
                               SlimePropertyMap properties) {
        this.asp = asp;
        this.loader = loader;
        this.dataSource = dataSource;
        this.properties = properties == null ? new SlimePropertyMap() : properties;
    }

    @Override
    public boolean isAvailable() {
        return asp != null && loader != null;
    }

    @Override
    public Object readTemplate(String templateName, boolean readOnly) throws Exception {
        return asp.readWorld(loader, templateName, readOnly, properties);
    }

    @Override
    public Object cloneInstance(Object template, String instanceName) {
        return ((SlimeWorld) template).clone(instanceName);
    }

    @Override
    public boolean loadWorld(Object world) {
        SlimeWorld loaded = asp.loadWorld((SlimeWorld) world, true);
        return loaded != null && asp.worldLoaded(loaded);
    }

    @Override
    public String describe() {
        return "AdvancedSlimePaper(" + dataSource + ")";
    }
}