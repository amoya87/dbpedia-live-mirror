package org.dbpedia.extraction.live.mirror;

import org.dbpedia.extraction.live.mirror.changesets.Changeset;
import org.dbpedia.extraction.live.mirror.changesets.ChangesetCounter;
import org.dbpedia.extraction.live.mirror.changesets.ChangesetExecutor;
import org.dbpedia.extraction.live.mirror.download.LastDownloadDateManager;
import org.dbpedia.extraction.live.mirror.helper.Global;
import org.dbpedia.extraction.live.mirror.helper.UpdateStrategy;
import org.dbpedia.extraction.live.mirror.helper.Utils;
import org.dbpedia.extraction.live.mirror.sparul.JDBCPoolConnection;
import org.dbpedia.extraction.live.mirror.sparul.SPARULGenerator;
import org.dbpedia.extraction.live.mirror.sparul.SPARULVosExecutor;
import org.slf4j.Logger;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: Mohamed Morsey
 * Date: 5/24/11
 * Time: 4:26 PM
 * This class is originally created from class defined in http://www.devdaily.com/java/edu/pj/pj010011
 * which is created by http://www.DevDaily.com
 */
public final class LiveSync {


    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(LiveSync.class);

    private static final String LAST_DOWNLOAD = "lastDownloadDate.dat";

    private static final int ERRORS_TO_ADVANCE = 3;

    private static final String EXTENSION_ADDED = ".added.nt.gz";
    private static final String EXTENSION_REMOVED = ".removed.nt.gz";
    private static final String EXTENSION_CLEAR = ".clear.nt.gz";
    private static final String EXTENSION_REINSERT = ".reinserted.nt.gz ";

    private LiveSync() {
    }

    public static void main(String[] args) {

        if (args != null && args.length > 2) {
            logger.error("Incorrect arguments in Main. Must be zero or one of {Endless|Onetime [2019-06-27-00-000019]}");
            System.exit(1);
        }
        UpdateStrategy strategy = UpdateStrategy.Endless; // default value
        String until = "9999-00-00-00-000000"; // default value
        if (args != null) {
        	if(args.length > 0) {
	            try {
	                strategy = UpdateStrategy.valueOf(args[0]);
	            } catch (Exception e) {
	                logger.error("Incorrect arguments in Main. Must be one of {Endless|Onetime [2019-06-27-00-000019]}");
	                System.exit(1);
	            }
	        }
        	if(args.length > 1) {
	            try {
	            	until = args[1];
	            } catch (Exception e) {
	                logger.error("Incorrect arguments in Main. Must be one of {Endless|Onetime [2019-06-27-00-000019]}");
	                System.exit(1);
	            }
	        }
        }

        // setup change executor
        ChangesetExecutor changesetExecutor = new ChangesetExecutor(new SPARULVosExecutor(), new SPARULGenerator(Global.getOptions().get("LiveGraphURI")));

        // Variable init from ini file
        String updateServerAddress = Global.getOptions().get("UpdateServerAddress");
        String updatesDownloadFolder = Global.getOptions().get("UpdatesDownloadFolder");
        long delayInSeconds = Long.parseLong(Global.getOptions().get("LiveUpdateInterval")) * 1000l;

        // Set latest applied patch
        ChangesetCounter lastDownload = LastDownloadDateManager.getLastDownloadDate(LAST_DOWNLOAD);
        ChangesetCounter currentCounter = new ChangesetCounter(lastDownload);
        ChangesetCounter untilDownload = new ChangesetCounter(until);
        currentCounter.advancePatch(); // move to next patch (this one is already applied

        // Download last published file from server
        String lastPublishedFilename = Global.getOptions().get("lastPublishedFilename");
        String lastPublishFileRemote = updateServerAddress + lastPublishedFilename;
        Utils.downloadFile(lastPublishFileRemote, updatesDownloadFolder);
        String lastPublishFileLocal = updatesDownloadFolder + lastPublishedFilename;
        ChangesetCounter remoteCounter = new ChangesetCounter(Utils.getFileAsString(lastPublishFileLocal));
        ChangesetCounter untilCounter = (untilDownload.compareTo(remoteCounter) > 0)?remoteCounter:untilDownload;

        int missing_urls = 0;
        while (true) {

            // when we are about to go beyond the remote published file
            if (currentCounter.compareTo(untilCounter) > 0) {

                /**
                 * TODO between the app started (or last fetch of last published file)
                 * the remote counter may be advanced but we don't take this into consideration here
                 * probably should re-download in a temp counter, check if different and continue without sleep
                 */

                // in case of onetime run, exit
                if (strategy.equals(UpdateStrategy.Onetime)) {
                    logger.info("Finished the One-Time update, exiting...");
                    break;
                }

                // sleep + download new file & continue
                logger.info("Up-to-date with last published changeset, sleeping for a while ;)");
                try {
                    Thread.sleep(delayInSeconds);
                } catch (InterruptedException e) {
                    logger.warn("Could not sleep...", e);
                }

                // code duplication
                Utils.downloadFile(lastPublishFileRemote, updatesDownloadFolder);
                remoteCounter = new ChangesetCounter(Utils.getFileAsString(lastPublishFileLocal));
                untilCounter = (untilDownload.compareTo(remoteCounter) > 0)? remoteCounter : untilDownload;

                //now we have an updated remote counter so next time this block will not run (if the updates are running)
                continue;

            }

            String addedTriplesURL = updateServerAddress + currentCounter.getFormattedFilePath() + EXTENSION_ADDED;
            String deletedTriplesURL = updateServerAddress + currentCounter.getFormattedFilePath() + EXTENSION_REMOVED;
            String clearTriplesURL = updateServerAddress + currentCounter.getFormattedFilePath() + EXTENSION_CLEAR;
            String reinsertTriplesURL = updateServerAddress + currentCounter.getFormattedFilePath() + EXTENSION_REINSERT;

            // changesets default to empty
            List<String> triplesToDelete = Arrays.asList();
            List<String> triplesToAdd = Arrays.asList();
            List<String> resourcesToClear = new LinkedList<>();
            List<String> triplesToReInsert = new LinkedList<>();

            //Download and decompress the file of deleted triples
            String addedCompressedDownloadedFile = Utils.downloadFile(addedTriplesURL, updatesDownloadFolder);
            String deletedCompressedDownloadedFile = Utils.downloadFile(deletedTriplesURL, updatesDownloadFolder);
            String clearCompressedDownloadedFile = Utils.downloadFile(clearTriplesURL, updatesDownloadFolder);
            String reinsertCompressedDownloadedFile = Utils.downloadFile(reinsertTriplesURL, updatesDownloadFolder);

            // Check for errors before proceeding
            if (addedCompressedDownloadedFile == null && deletedCompressedDownloadedFile == null && clearCompressedDownloadedFile == null && reinsertCompressedDownloadedFile == null) {
                missing_urls++;
                if (missing_urls >= ERRORS_TO_ADVANCE) {
                    // advance hour / day / month or year
                    currentCounter.advanceHour();
                }
                continue;
            }
            // URL works, reset missing URLs
            missing_urls = 0;

            if (clearCompressedDownloadedFile != null) {

                String file = Utils.decompressGZipFile(clearCompressedDownloadedFile);
                List<String> temp_triples = Utils.getTriplesFromFile(file);
                for (String triple : temp_triples) {
                    String[] splittedTriple = triple.split("> <");
                    String tmp_resource = splittedTriple[0];
                    String resource = tmp_resource.substring(1);
                    resourcesToClear.add(resource);
                }
                Utils.deleteFile(file);
            }

            if (deletedCompressedDownloadedFile != null) {

                String file = Utils.decompressGZipFile(deletedCompressedDownloadedFile);
                triplesToDelete = Utils.getTriplesFromFile(file);
                Utils.deleteFile(file);
            }


            if (addedCompressedDownloadedFile != null) {
                String decompressedAddedNTriplesFile = Utils.decompressGZipFile(addedCompressedDownloadedFile);
                triplesToAdd = Utils.getTriplesFromFile(decompressedAddedNTriplesFile);

                Utils.deleteFile(decompressedAddedNTriplesFile);
            }

            if (reinsertCompressedDownloadedFile != null) {
                String decompressedReInsertNTriplesFile= Utils.decompressGZipFile(reinsertCompressedDownloadedFile);
                triplesToReInsert = Utils.getTriplesFromFile(decompressedReInsertNTriplesFile);

                Utils.deleteFile(decompressedReInsertNTriplesFile);
            }

            Changeset changeset = new Changeset(currentCounter.toString(), triplesToAdd, triplesToDelete, resourcesToClear, triplesToReInsert);
            changesetExecutor.applyChangeset(changeset);


            // save last processed date
            LastDownloadDateManager.writeLastDownloadDate(LAST_DOWNLOAD, currentCounter.toString());

            // advance to the next patch
            currentCounter.advancePatch();

        }

        JDBCPoolConnection.shutdown();

    }  // end of main

}
