package makamys.mclib.core;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;

import cpw.mods.fml.common.LoadController;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;

public class MCLib {
	
	public static final String VERSION = "0.1.3";
	
	private static MCLib instance;
	
	public static Logger LOGGER;
	public static final Logger GLOGGER = LogManager.getLogger("mclib");
	
	public MCLib(boolean subscribe) {
		String modid = Loader.instance().activeModContainer().getModId();
		LOGGER = LogManager.getLogger("mclib(" + modid + ")");
		
		MCLibModules.init();
		
		if(subscribe) {
			try {
				LoadController lc = ReflectionHelper.getPrivateValue(Loader.class, Loader.instance(), "modController");
				EventBus masterChannel = ReflectionHelper.getPrivateValue(LoadController.class, lc, "masterChannel");
				masterChannel.register(this);
			} catch(Exception e) {
				LOGGER.error("Failed to subscribe to LoadController's bus. The state change event handlers will have to be called manually from your mod.");
				e.printStackTrace();
			}
		}
	}
	
	/**
	 * Call this in your FMLConstructionEvent handler to initialize the library framework.
	 */
	public static void init() {
		init(true);
	}
	
	public static void init(boolean subscribe) {
		instance = new MCLib(subscribe);
	}
	
	@Subscribe
    public void preInit(FMLPreInitializationEvent event) {
		LOGGER.info("preinit");
	}
	
}
