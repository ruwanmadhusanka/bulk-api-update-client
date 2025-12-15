package com.sample.utilities;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class ReadConfigFile {
    private Properties properties = new Properties();

    /**
     * Read config.file.
     */
    public ReadConfigFile() {
        try (InputStream input = new FileInputStream("config.properties")) {
            properties.load(input);
        } catch (IOException e) {
            System.out.println("Can't find/read 'config.properties' file. Make sure the 'config.properties' is" +
                    " located with the running jar file.");
            e.printStackTrace();
        }
    }

    /**
     * get the value related to a property.
     * @param key key of a property.
     * @return value of property.
     */
    public String getProperty(String key) {
        return properties.getProperty(key);
    }
}
