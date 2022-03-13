package be.garagepoort.mcioc.configuration.files;

import be.garagepoort.mcioc.TubingPlugin;
import org.apache.commons.lang.Validate;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ConfigurationUtil {

    private static final Logger logger = TubingPlugin.getPlugin().getLogger();

    private ConfigurationUtil() {
    }

    public static void saveConfiguration(String configurationFile) {
        File dataFolder = TubingPlugin.getPlugin().getDataFolder();
        String fullConfigResourcePath = (configurationFile).replace('\\', '/');

        InputStream in = getResource(fullConfigResourcePath);
        if (in == null) {
            logger.log(Level.SEVERE, "Could not find configuration file " + fullConfigResourcePath);
            return;
        }

        File outFile = new File(dataFolder, fullConfigResourcePath);
        int lastIndex = fullConfigResourcePath.lastIndexOf(47);
        File outDir = new File(dataFolder, fullConfigResourcePath.substring(0, Math.max(lastIndex, 0)));
        if (!outDir.exists()) {
            outDir.mkdirs();
        }

        try {
            if (!outFile.exists()) {
                try (OutputStream out = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[1024];

                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                }
                in.close();
            }
        } catch (IOException var10) {
            logger.log(Level.SEVERE, "Could not save " + outFile.getName() + " to " + outFile, var10);
        }
    }

    private static InputStream getResource(String filename) {
        try {
            URL url = ConfigurationUtil.class.getClassLoader().getResource(filename);
            if (url == null) {
                return null;
            } else {
                URLConnection connection = url.openConnection();
                connection.setUseCaches(false);
                return connection.getInputStream();
            }
        } catch (IOException var4) {
            return null;
        }
    }

    public static FileConfiguration loadConfiguration(String path) {
        File file = Paths.get(TubingPlugin.getPlugin().getDataFolder() + File.separator + path).toFile();

        Validate.notNull(file, "File cannot be null");
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(file);
        } catch (Exception e) {
            throw new ConfigurationException("Cannot load " + file, e);
        }

        return config;
    }

    public static Map<String, String> loadFilters(String filtersString) {
        Map<String, String> filterMap = new HashMap<>();
        if (filtersString != null) {
            String[] split = filtersString.split(";");
            for (String filter : split) {
                String[] filterPair = filter.split("=");
                filterMap.put(filterPair[0].toLowerCase(), filterPair[1].toLowerCase());
            }
        }
        return filterMap;
    }
}
