package edu.hms.harvard.sorgerlab.minerva;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.logging.LogManager;
import java.util.Set;
import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;

import com.amazonaws.services.simplesystemsmanagement.AWSSimpleSystemsManagementClientBuilder;
import com.amazonaws.services.simplesystemsmanagement.model.GetParameterRequest;
import com.amazonaws.services.stepfunctions.model.StartExecutionRequest;
import com.amazonaws.services.stepfunctions.AWSStepFunctionsClientBuilder;
import com.amazonaws.services.stepfunctions.AWSStepFunctions;
import loci.formats.ClassList;
import loci.formats.FormatException;
import loci.formats.IFormatReader;
import loci.formats.ImageReader;
import loci.formats.in.ZipReader;
import org.apache.commons.io.DirectoryWalker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Directory walker using Bio-Formats to attempt to read any files that are
 * found. Finding a file may lead to a large set of files being identified as a
 * set and subsequently skipped when reached by the walker.
 *
 * Upon identifying a set of files, initiates a step function that is
 * responsible for metadata extraction and tiling of those files.
 */
public class BFScanner extends DirectoryWalker {

    /**
     * Logger
     */
    private static final Logger logger =
        LoggerFactory.getLogger(BFScanner.class);

    /**
     * Environment variable with the stack prefix
     */
    private static final String stackPrefix = System.getenv("STACKPREFIX");

    /**
     * Environment variable with the stage
     */
    private static final String stage = System.getenv("STAGE");

    /**
     * Classes of all default Bio-Formats readers except zip
     */
    // private static final ClassList<IFormatReader> readers;
    // static {
    //     readers = ImageReader.getDefaultReaderClasses();
    //     readers.removeClass(ZipReader.class);
    // }

    /**
     * AWS Step Function client
     */
    private static final AWSStepFunctions step =
        AWSStepFunctionsClientBuilder.defaultClient();

    /**
     * AWS ARN of Bio-Formats Extraction Step Function
     */
    // private static final String bfimportARN =
    //     AWSSimpleSystemsManagementClientBuilder.defaultClient()
    //         .getParameter(new GetParameterRequest()
    //             .withName(String.format("/%s/%s/batch/bfimportWorkflowARN",
    //                                     stackPrefix, stage))
    //         )
    //         .getParameter()
    //         .getValue();

    /**
     * Lookup of files that have been claimed by previous filesets that should
     * not be reconsidered.
     */
    final private Set<String> claimedFiles = new HashSet<String>();

    /**
     * Absolute path to directory to scan
     */
    final private Path scanDir;

    /**
     * Constructor
     * @param scanDir Relative path to directory to scan from working directory
     */
    public BFScanner(Path scanDir) {
        super();
        this.scanDir = scanDir;
    }

    /**
     * Main method
     * @param args Arguments. Expects exactly one path to scan relative to
     *             working directory.
     */
    public static void main( String[] args ) {

        // Ensure there is an argument representing the directory to scan
        if (args.length != 1) {
            logger.error("No directory to scan was specified");
            System.exit(1);
            return;
        }

        // Path to the directory to scan (it is assumed that the working
        // directory is the parent of the directory to scan)
        Path scanDir = Paths.get(args[0]).toAbsolutePath();

        // Ensure the directory to scan is actually a directory
        if (!Files.isDirectory(scanDir)) {
            logger.error(String.format("Directory was not present to scan %s",
                                        scanDir.toString()));
            System.exit(1);
            return;
        }

        BFScanner bfscanner = new BFScanner(scanDir);

        try {
            bfscanner.walk(scanDir.toFile(), new ArrayList());
        } catch (IOException e) {
            logger.error(String.format("Input/Out Error processing %s", e));
            System.exit(1);
            return;
        }

    }

    /**
     * Attempt to use Bio-formats to read each file found (and not skipped
     * because of a previous claim) by the directory walker.
     * @param  file        File to attempt to read
     * @param  depth       Recursion depth
     * @param  results     Accumulated results (unused)
     */
    protected void handleFile(File file, int depth, Collection results)
            throws IOException {

        // Get absolute path of this file
        String absPath = file.getAbsolutePath();

        // Skip files that have already been claimed as part of another set
        if (claimedFiles.contains(absPath)) {
            return;
        }

        // String readersPath = null;

        // Create a reader and set it to read current file. Skips if the file
        // is not readable by Bio-Formats
        ImageReader reader = new ImageReader();

        try {
            reader.setId(absPath);
        } catch (FormatException e) {
            // No reader can read this file
            logger.info(String.format("File not readable by bioformats: %s",
                                      file.getPath().toString()));
            return;
        }

        // Get the information about this BFU
        String[] usedFiles = reader.getUsedFiles();
        String readerClass = reader.getReader().getClass().getName();

        // Mark these files as claimed so that they will not be read again
        claimedFiles.addAll(Arrays.asList(usedFiles));

        // Add to results
        results.add(usedFiles);

        // Make a list of all the used paths relative to the scanned directory
        List<Path> usedPaths = new ArrayList<Path>();
        for (String usedFile : usedFiles) {
            usedPaths.add(this.scanDir.relativize(Paths.get(usedFile)));
        }

        // Build JSON input
        JsonArrayBuilder pathBuilder = Json.createArrayBuilder();
        for (Path path : usedPaths) {
            pathBuilder.add(path.normalize().toString());
        }
        JsonArray paths = pathBuilder.build();
        JsonObject object = Json.createObjectBuilder()
            .add("paths", paths)
            .add("reader", readerClass)
            .build();

        System.out.print(object.toString());

        // Call a lambda function to record this BFU in the database and
        // initiate the metadata extraction and tiling of those files
        // TODO Connect to real lambda function

        // step.startExecution(
        //     new StartExecutionRequest()
        //         .withStateMachineArn()
        //         .withInput()
        //         .withName()
        // );

    }
}
