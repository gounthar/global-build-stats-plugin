package hudson.plugins.global_build_stats.model;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.Hudson;
import hudson.plugins.global_build_stats.util.CollectionsUtil;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author fcamblor
 * POJO which responsibility is to :
 * - Shard JobBuildResult into several monthly files when saving / loading JobBuildResults
 * - Allow to queue add and remove of job build results before a save
 */
public class JobBuildResultSharder {

    private static final Logger LOGGER = Logger.getLogger(JobBuildResultSharder.class.getName());

    // Path, from jenkins_home, to global-build-stats folder
    private static final String GBS_ROOT_PATH = "global-build-stats";
    // Path, from GBS_ROOT_PATH, to job results folder
    private static final String GBS_JOBRESULTS_PATH = "jobresults";

    /**
     * Hand-off queue from the event callback of {@link hudson.plugins.global_build_stats.business.GlobalBuildStatsPluginSaver.BeforeSavePluginCallback}
     * to the thread that's adding the records. Access needs to be synchronized.
     */
    private final List<JobBuildResult> queuedResultsToAdd = Collections.synchronizedList(new ArrayList<JobBuildResult>());

    /**
     * @see #queuedResultsToAdd
     */
    private final List<JobBuildResult> queuedResultsToRemove = Collections.synchronizedList(new ArrayList<JobBuildResult>());

    /**
     * Effective persisted results list
     */
    private final List<JobBuildResult> persistedResults;
    /**
     * Effective persisted results map
     * Note: persistedResults & persistedMonthlyResults are always coherent
     */
    private final Map<String, List<JobBuildResult>> persistedMonthlyResults;

    public JobBuildResultSharder(){
        this(null, new ArrayList<JobBuildResult>());
    }

    public JobBuildResultSharder(JobBuildResultSharder sharder, List<JobBuildResult> jobBuildResults){
        this.persistedResults = Collections.synchronizedList(jobBuildResults);
        this.persistedMonthlyResults = Collections.synchronizedMap(toJobResultFilenameMap(jobBuildResults));
        if(sharder != null){
            this.queueResultsToAdd(sharder.queuedResultsToAdd);
            this.queueResultsToRemove(sharder.queuedResultsToRemove);
        }
    }

    public void queueResultToAdd(JobBuildResult result){
        queuedResultsToAdd.add(result);
    }

    public void queueResultsToAdd(List<JobBuildResult> results){
        queuedResultsToAdd.addAll(results);
    }

    public void queueResultToRemove(JobBuildResult result){
        queuedResultsToRemove.add(result);
    }

    public void queueResultsToRemove(List<JobBuildResult> results){
        queuedResultsToRemove.addAll(results);
    }

    @SuppressFBWarnings("DM_DEFAULT_ENCODING")
    public void applyQueuedResultsInFiles(){
        LOGGER.log(Level.FINER, "Processing job results update queue ...");
        // atomically move all the queued stuff into a local list
        List<JobBuildResult> resultsToAdd;
        synchronized (queuedResultsToAdd) {
            resultsToAdd = new ArrayList<JobBuildResult>(queuedResultsToAdd);
            queuedResultsToAdd.clear();
        }

        List<JobBuildResult> resultsToRemove;
        // atomically move all the queued stuff into a local list
        synchronized (queuedResultsToRemove) {
            resultsToRemove = new ArrayList<JobBuildResult>(queuedResultsToRemove);
            queuedResultsToRemove.clear();
        }

        // this happens if other runnables have written bits in a bulk
        if (resultsToAdd.isEmpty() && resultsToRemove.isEmpty()){
            LOGGER.log(Level.FINER, "No change detected in job results update queue !");
            return;
        }

        File jobResultsRoot = getJobResultFolder();
        if(!jobResultsRoot.exists()){
            try {
                FileUtils.forceMkdir(jobResultsRoot);
            } catch (IOException e) {
                throw new IllegalStateException("Can't create job results root directory : "+jobResultsRoot.getAbsolutePath(), e);
            }
        }

        // Persisting everything
        removePersistedJobResults(resultsToRemove);
        addPersistedJobResults(resultsToAdd);

        List<String> updatedFilenamesList = new ArrayList<String>(toJobResultFilenameMap(resultsToRemove).keySet());
        updatedFilenamesList.addAll(toJobResultFilenameMap(resultsToAdd).keySet());
        Collection<String> updatedFilenames = CollectionsUtil.toSet(updatedFilenamesList);

        for(String filename : updatedFilenames){
            String jobResultFilepath = jobResultsRoot.getAbsolutePath() + File.separator + filename;
            try {
                FileWriter fw = new FileWriter(jobResultFilepath);
                Hudson.XSTREAM.toXML(persistedMonthlyResults.get(filename), fw);
                fw.close();
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Unable to serialize job results into "+jobResultFilepath, e);
                throw new IllegalStateException("Unable to serialize job results into "+jobResultFilepath, e);
            }
        }
        LOGGER.log(Level.FINER, "Queued changes applied on job results !");
    }

    @SuppressFBWarnings({"NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE", "DM_DEFAULT_ENCODING"})
    public static List<JobBuildResult> load(){
        List<JobBuildResult> jobBuildResults = new ArrayList<JobBuildResult>();
        File jobResultsRoot = getJobResultFolder();
        if(jobResultsRoot.exists()){
            for(File f: jobResultsRoot.listFiles()){
                try {
                    FileReader fr = new FileReader(f);
                    List<JobBuildResult> jobResultsInFile = (List<JobBuildResult>)Hudson.XSTREAM.fromXML(fr);
                    jobBuildResults.addAll(jobResultsInFile);
                    fr.close();
                } catch (IOException e) {
                    LOGGER.log(Level.SEVERE, "Unable to read job results in "+f.getAbsolutePath(), e);
                    throw new IllegalStateException("Unable to read job results in "+f.getAbsolutePath(), e);
                }
            }
        }
        Collections.sort(jobBuildResults, new JobBuildResult.AntiChronologicalComparator());
        return jobBuildResults;
    }

    private synchronized void addPersistedJobResults(List<JobBuildResult> results){
        persistedResults.addAll(results);
        Map<String, List<JobBuildResult>> filenameMap = toJobResultFilenameMap(results);
        CollectionsUtil.mapMergeAdd(persistedMonthlyResults, filenameMap);
    }

    private synchronized void removePersistedJobResults(List<JobBuildResult> results){
        persistedResults.removeAll(results);
        Map<String, List<JobBuildResult>> filenameMap = toJobResultFilenameMap(results);
        CollectionsUtil.mapMergeRemove(persistedMonthlyResults, filenameMap);
    }

    public List<JobBuildResult> getJobBuildResults(){
        List<JobBuildResult> aggregatedList = new ArrayList<JobBuildResult>(this.persistedResults);
        aggregatedList.removeAll(queuedResultsToRemove);
        aggregatedList.addAll(queuedResultsToAdd);
        return Collections.unmodifiableList(aggregatedList);
    }

    public boolean pendingChanges(){
        return !queuedResultsToAdd.isEmpty() || !queuedResultsToRemove.isEmpty();
    }
    
    /**
     * Transforming given JobBuildResult list into a map of type [filename of monthly job result file => list of job results]
     */
    private static Map<String, List<JobBuildResult>> toJobResultFilenameMap(List<JobBuildResult> results){
        // Sharding job build results depending on their year+month
        Map<String, List<JobBuildResult>> byMonthJobResults = new HashMap<String, List<JobBuildResult>>();
        SimpleDateFormat yearMonth = new SimpleDateFormat("yyyy-MM");
        for(JobBuildResult r: results){
            String targetFilename = "jobResults-" + yearMonth.format(r.getBuildDate().getTime()) + ".xml";
            if(!byMonthJobResults.containsKey(targetFilename)){
                byMonthJobResults.put(targetFilename, new ArrayList<JobBuildResult>());
            }
            byMonthJobResults.get(targetFilename).add(r);
        }

        return byMonthJobResults;
    }

    private static File getJobResultFolder(){
        return new File(
                Hudson.getInstance().getRootDir().getAbsolutePath() + File.separator +
                        GBS_ROOT_PATH + File.separator + GBS_JOBRESULTS_PATH);
    }
}
