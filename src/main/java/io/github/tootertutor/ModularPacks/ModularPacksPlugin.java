package io.github.tootertutor.ModularPacks;

import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import io.github.tootertutor.ModularPacks.commands.CommandRouter;
import io.github.tootertutor.ModularPacks.commands.sub.GiveSubcommand;
import io.github.tootertutor.ModularPacks.commands.sub.ListSubcommand;
import io.github.tootertutor.ModularPacks.commands.sub.OpenSubcommand;
import io.github.tootertutor.ModularPacks.commands.sub.RefreshSkullsSubcommand;
import io.github.tootertutor.ModularPacks.commands.sub.RecoverSubcommand;
import io.github.tootertutor.ModularPacks.commands.sub.ReloadSubcommand;
import io.github.tootertutor.ModularPacks.commands.sub.SetTypeSubcommand;
import io.github.tootertutor.ModularPacks.config.ConfigManager;
import io.github.tootertutor.ModularPacks.config.LangManager;
import io.github.tootertutor.ModularPacks.data.SQLiteBackpackRepository;
import io.github.tootertutor.ModularPacks.gui.BackpackMenuRenderer;
import io.github.tootertutor.ModularPacks.item.Keys;
import io.github.tootertutor.ModularPacks.listeners.AnvilModuleListener;
import io.github.tootertutor.ModularPacks.listeners.BackpackEverlastingListener;
import io.github.tootertutor.ModularPacks.listeners.BackpackMenuListener;
import io.github.tootertutor.ModularPacks.listeners.BackpackUseListener;
import io.github.tootertutor.ModularPacks.listeners.ClickDebugListener;
import io.github.tootertutor.ModularPacks.listeners.CraftingModuleListener;
import io.github.tootertutor.ModularPacks.listeners.FurnaceModuleListener;
import io.github.tootertutor.ModularPacks.listeners.ModuleRecipeListener;
import io.github.tootertutor.ModularPacks.listeners.ModuleFilterScreenListener;
import io.github.tootertutor.ModularPacks.listeners.PreventModulePlacementListener;
import io.github.tootertutor.ModularPacks.listeners.PreventModuleUseListener;
import io.github.tootertutor.ModularPacks.listeners.PreventNestingListener;
import io.github.tootertutor.ModularPacks.listeners.SmithingModuleListener;
import io.github.tootertutor.ModularPacks.listeners.StonecutterModuleListener;
import io.github.tootertutor.ModularPacks.modules.ModuleEngineService;
import io.github.tootertutor.ModularPacks.recipes.RecipeManager;

public final class ModularPacksPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private LangManager langManager;
    private SQLiteBackpackRepository repository;
    private Keys keys;
    private ModuleEngineService engines;
    private ClickDebugListener clickDebug;
    private RecipeManager recipes;
    private BackpackSessionManager sessions;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("lang/en_us.yml", false);

        this.keys = new Keys(this);

        this.configManager = new ConfigManager(this);
        this.configManager.reload();

        this.langManager = new LangManager(this);
        this.langManager.reload();

        this.repository = new SQLiteBackpackRepository(this);
        this.repository.init();

        this.sessions = new BackpackSessionManager(this);

        this.engines = new ModuleEngineService(this);
        this.engines.start();

        this.recipes = new RecipeManager(this);
        this.recipes.reload();
        Bukkit.getPluginManager().registerEvents(this.recipes, this);

        BackpackMenuRenderer renderer = new BackpackMenuRenderer(this);

        Bukkit.getPluginManager().registerEvents(new BackpackUseListener(this), this);
        Bukkit.getPluginManager().registerEvents(new BackpackMenuListener(this, renderer), this);
        Bukkit.getPluginManager().registerEvents(new ModuleRecipeListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ModuleFilterScreenListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PreventNestingListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PreventModulePlacementListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PreventModuleUseListener(this), this);
        Bukkit.getPluginManager().registerEvents(new BackpackEverlastingListener(this), this);
        Bukkit.getPluginManager().registerEvents(new AnvilModuleListener(this), this);
        Bukkit.getPluginManager().registerEvents(new FurnaceModuleListener(this), this);
        Bukkit.getPluginManager().registerEvents(new CraftingModuleListener(this), this);
        Bukkit.getPluginManager().registerEvents(new SmithingModuleListener(this), this);
        Bukkit.getPluginManager().registerEvents(new StonecutterModuleListener(this), this);

        if (cfg().debugClickLog()) {
            this.clickDebug = new ClickDebugListener(this);
            this.clickDebug.start();
            Bukkit.getPluginManager().registerEvents(this.clickDebug, this);
            getLogger().warning("Click debug logging is enabled; writing to click-events.log");
        }

        CommandRouter router = new CommandRouter(this);
        router.register(new GiveSubcommand(this));
        router.register(new ListSubcommand(this));
        router.register(new OpenSubcommand(this));
        router.register(new RecoverSubcommand(this));
        router.register(new ReloadSubcommand(this));
        router.register(new SetTypeSubcommand(this));
        router.register(new RefreshSkullsSubcommand(this));
        getCommand("backpack").setExecutor(router);
        getCommand("backpack").setTabCompleter(router);

        getLogger().info("modularpacks enabled.");

    }

    @Override
    public void onDisable() {
        if (clickDebug != null)
            clickDebug.stop();

        // remove recipes on disable so reloads don't duplicate
        if (recipes != null)
            recipes.close();

        if (repository != null)
            repository.close();

        if (engines != null)
            engines.stop();

        getLogger().info("modularpacks disabled.");
    }

    public ConfigManager cfg() {
        return configManager;
    }

    public LangManager lang() {
        return langManager;
    }

    public SQLiteBackpackRepository repo() {
        return repository;
    }

    public Keys keys() {
        return keys;
    }

    public RecipeManager recipes() {
        return recipes;
    }

    public BackpackSessionManager sessions() {
        return sessions;
    }

    public void reloadAll() {
        cfg().reload();
        lang().reload();
        if (recipes != null)
            recipes.reload();

        boolean wantClickLog = cfg().debugClickLog();
        if (wantClickLog && clickDebug == null) {
            this.clickDebug = new ClickDebugListener(this);
            this.clickDebug.start();
            Bukkit.getPluginManager().registerEvents(this.clickDebug, this);
            getLogger().warning("Click debug logging is enabled; writing to click-events.log");
        } else if (!wantClickLog && clickDebug != null) {
            HandlerList.unregisterAll(clickDebug);
            clickDebug.stop();
            clickDebug = null;
        }
    }
}
