package noxy;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;

public class Main {
    public static void main(String[] args) {
        Options options = new Options();

        options.addRequiredOption("c", "config", true, "Configuration file path");
        options.addOption("h", "help", false, "Show help");

        HelpFormatter formatter = new HelpFormatter();

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd;
        try {
            cmd = parser.parse(options, args);
        } catch (Exception e) {
            System.err.println("Error loading config file: "+e);
            formatter.printHelp("noxy", options);
            System.exit(1);
            return;
        }

        if (cmd.hasOption("help")) {
            formatter.printHelp("noxy", options);
            System.exit(0);
        }


        Config config = new Config();
        config.load(cmd.getOptionValue("config"));
        new ServerInit(config);
    }

    public String getGreeting() {
        return "";
    }
}
