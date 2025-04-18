package hudson.plugins.global_build_stats;

import hudson.Extension;
import hudson.Plugin;
import hudson.model.ManagementLink;
import hudson.model.TaskListener;
import hudson.model.Api;
import hudson.model.Hudson;
import hudson.model.Run;
import hudson.model.listeners.ItemListener;
import hudson.model.listeners.RunListener;
import hudson.plugins.global_build_stats.business.GlobalBuildStatsBusiness;
import hudson.plugins.global_build_stats.model.*;
import hudson.plugins.global_build_stats.rententionstrategies.RetentionStrategy;
import hudson.plugins.global_build_stats.validation.GlobalBuildStatsValidator;
import hudson.security.Permission;
import hudson.util.ChartUtil;
import hudson.util.FormValidation;
import java.io.File;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.servlet.ServletException;

import net.sf.json.JSONObject;

import org.jfree.chart.JFreeChart;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;
import org.kohsuke.stapler.export.Flavor;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * Entry point of the global build stats plugin
 * 
 * @author fcamblor
 */
@ExportedBean
public class GlobalBuildStatsPlugin extends Plugin {

    private static final Logger LOGGER = Logger.getLogger(GlobalBuildStatsPlugin.class.getName());

    /**
     * List of aggregated job build results
     * This list will grow over time, but will be monthly sharded in different files to keep
     * save() time constant
     */
    private JobBuildResultSharder jobBuildResultsSharder = new JobBuildResultSharder();

	/**
	 * List of aggregated job build results
	 * This list will grow over time
     * @deprecated Use jobBuildResultsSharder instead of jobBuildResults (since v8 file format)
	 */
    @Deprecated
	private transient List<JobBuildResult> jobBuildResults = new ArrayList<JobBuildResult>();
	
	/**
	 * List of persisted build statistics configurations used on the
	 * global build stats screen
	 */
	private List<BuildStatConfiguration> buildStatConfigs = new ArrayList<BuildStatConfiguration>();

    /**
     * List of retention strategies applied on job results
     */
    private List<RetentionStrategy> retentionStrategies = new ArrayList<RetentionStrategy>();
	
	/**
	 * Business layer for global build stats
	 */
	private transient final GlobalBuildStatsBusiness business = new GlobalBuildStatsBusiness(this);
	
	/**
	 * Validator layer for global build stats
	 */
	private transient final GlobalBuildStatsValidator validator = new GlobalBuildStatsValidator();
	
	/**
	 * application/json ContentType for StaplerResponse2
	 */
	private final static String CONTENT_TYPE = "application/json";
	
    /**
     * Expose {@link GlobalBuildStatsPlugin} to the remote API :
     * - Either all build stat configuration data
     * - OR (if buildStatConfigId http parameter is given) chart data
     */
    public Api getApi() {
    	return new GlobalBuildStatsApi(this);
    }

    /**
     * Highered visibility of load method
     */
    @Override
    public void load() throws IOException {
        super.load();
    }

    public File getConfigXmlFile() {
        return getConfigXml().getFile();
    }

    /**
     * Hack allowing to either generate plugin informations (build stat configurations) OR
     * generate chart data for a given buildStatConfigId request parameter
     * @author fcamblor
     */
    public static class GlobalBuildStatsApi extends Api{
    	public GlobalBuildStatsApi(Object bean) {
    		super(bean);
		}
    	@Override
    	public void doJson(StaplerRequest2 req, StaplerResponse2 rsp)
    			throws IOException, ServletException {
    		if(!exposeChartData(req, rsp, Flavor.JSON)){
    			super.doJson(req, rsp);
    		}
    	}
    	@Override
    	public void doPython(StaplerRequest2 req, StaplerResponse2 rsp)
    			throws IOException, ServletException {
    		if(!exposeChartData(req, rsp, Flavor.PYTHON)){
        		super.doPython(req, rsp);
    		}
    	}
    	
    	private static boolean exposeChartData(StaplerRequest2 req, StaplerResponse2 rsp, Flavor flavor) throws ServletException, IOException{
    		boolean chartDataHasBeenExposed = false;
    		String buildStatConfigId = req.getParameter("buildStatConfigId");
    		if(buildStatConfigId != null){
    	    	BuildStatConfiguration config = GlobalBuildStatsPlugin.getPluginBusiness().searchBuildStatConfigById(buildStatConfigId);
    	    	if(config != null){
    	    		List<AbstractBuildStatChartDimension> dimensions = GlobalBuildStatsPlugin.getPluginBusiness().createDataSetBuilder(config);
    	    		rsp.serveExposedBean(req, new BuildStatChartData(dimensions), flavor);
    	    		chartDataHasBeenExposed = true;
    	    	}
    		}
    		return chartDataHasBeenExposed;
    	}
    }
	
    @Extension
    public static class GlobalBuildStatsItemListener extends ItemListener {
    	/**
    	 * After all items are loaded, plugin is loaded
    	 */
    	@Override
    	public void onLoaded() {
    		super.onLoaded();

            getPluginBusiness().reloadPlugin();
    	}
    	
    	// TODO: check if a node has been renamed and, if so, replace old name by new name in
    	// every job results
    }
	
	/**
	 * Let's add a link in the administration panel linking to the global build stats page
	 */
    @Extension
    public static class GlobalBuildStatsManagementLink extends ManagementLink {

    	@Override
        public String getIconFileName() {
            return "symbol-bar-chart-outline plugin-ionicons-api";
        }

    	@Override
        public String getDisplayName() {
            return Messages.Global_Builds_Stats();
        }

    	@Override
        public String getUrlName() {
            return "plugin/global-build-stats/";
        }
        
        @Override 
        public String getDescription() {
            return Messages.Displays_stats_about_daily_build_results();
        }
    }
    
    /**
     * At the end of every jobs, let's gather job result informations into global build stats
     * persisted data
     */
    @Extension
    public static class GlobalBuildStatsRunListener extends RunListener<Run<?, ?>>{
    	@Override
    	public void onCompleted(Run<?, ?> r, TaskListener listener) {
            LOGGER.log(Level.FINEST, "GlobalBuildStatsRunListener onCompleted " + r.getExternalizableId());
    		getPluginBusiness().onJobCompleted(r);
    	}

        @Override
        public void onDeleted(Run<?, ?> build) {
            getPluginBusiness().onBuildDeleted(build);
        }
    }
    
    public static GlobalBuildStatsBusiness getPluginBusiness() {
		// Retrieving global build stats plugin & adding build result to the registered build
		// result
    	return getInstance().business;
    }
    
    public static GlobalBuildStatsPlugin getInstance(){
    	return Hudson.getInstance().getPlugin(GlobalBuildStatsPlugin.class);
    }

    // Form validations
    
    public FormValidation doCheckJobFilter(@QueryParameter String value){
    	return validator.checkJobFilter(value);
    }
    
    public FormValidation doCheckFailuresShown(@QueryParameter String value){
    	return validator.checkFailuresShown(value);
    }
    
    public FormValidation doCheckUnstablesShown(@QueryParameter String value){
    	return validator.checkUnstablesShown(value);
    }
    
    public FormValidation doCheckAbortedShown(@QueryParameter String value){
    	return validator.checkAbortedShown(value);
    }
    
    public FormValidation doCheckNotBuildsShown(@QueryParameter String value){
    	return validator.checkNotBuildsShown(value);
    }
    
    public FormValidation doCheckSuccessShown(@QueryParameter String value){
    	return validator.checkSuccessShown(value);
    }
    
	public FormValidation doCheckHistoricScale(@QueryParameter String value){
    	return validator.checkHistoricScale(value);
    }

	public FormValidation doCheckHistoricLength(@QueryParameter String value){
    	return validator.checkHistoricLength(value);
    }

    public FormValidation doCheckBuildStatHeight(@QueryParameter String value){
    	return validator.checkBuildStatHeight(value);
    }

    public FormValidation doCheckBuildStatWidth(@QueryParameter String value){
    	return validator.checkBuildStatWidth(value);
    }

    public FormValidation doCheckTitle(@QueryParameter String value){
    	return validator.checkTitle(value);
    }

    public FormValidation doCheckYAxisChartType(@QueryParameter String value){
    	return validator.checkYAxisChartType(value);
    }

    public HttpResponse doRecordBuildInfos() throws IOException {
    	Hudson.getInstance().checkPermission(getRequiredPermission());
    	
    	business.recordBuildInfos();
    	
        return new HttpResponse() {
        	@Override
			public void generateResponse(StaplerRequest2 req, StaplerResponse2 rsp,
					Object node) throws IOException, ServletException {
			}
		};
    }
    
    public void doShowChart(StaplerRequest2 req, StaplerResponse2 res) throws ServletException, IOException {
    	// Don't check any role : this url is public and should provide a BuildStatConfiguration public id
    	BuildStatConfiguration config = business.searchBuildStatConfigById(req.getParameter("buildStatId"));
    	if(config == null){
    		throw new IllegalArgumentException("Unknown buildStatId parameter !");
    	}
    	JFreeChart chart = business.createChart(config);
    	
        ChartUtil.generateGraph(org.kohsuke.stapler.StaplerRequest.fromStaplerRequest2(req),
                                org.kohsuke.stapler.StaplerResponse.fromStaplerResponse2(res),
                                chart,
                                config.getBuildStatWidth(),
                                config.getBuildStatHeight());
    }
    
    public void doCreateChart(StaplerRequest2 req, StaplerResponse2 res) throws ServletException, IOException {
    	Hudson.getInstance().checkPermission(getRequiredPermission());
    	
    	// Passing null id since this is a not persisted BuildStatConfiguration
    	BuildStatConfiguration config = FromRequestObjectFactory.createBuildStatConfiguration(null, req);
    	JFreeChart chart = business.createChart(config);
    	
        ChartUtil.generateGraph(org.kohsuke.stapler.StaplerRequest.fromStaplerRequest2(req),
                                org.kohsuke.stapler.StaplerResponse.fromStaplerResponse2(res),
                                chart,
                                config.getBuildStatWidth(),
                                config.getBuildStatHeight());
    }
    
    public void doCreateChartMap(StaplerRequest2 req, StaplerResponse2 res) throws ServletException, IOException {
    	Hudson.getInstance().checkPermission(getRequiredPermission());

    	String buildStatId = req.getParameter("buildStatId");
    	BuildStatConfiguration config;
    	if(buildStatId != null){
    		config = business.searchBuildStatConfigById(buildStatId);
    	} else {
        	// Passing null id since this is a not persisted BuildStatConfiguration
        	config = FromRequestObjectFactory.createBuildStatConfiguration(null, req);
    	}
    	JFreeChart chart = business.createChart(config);
    	
        ChartUtil.generateClickableMap(org.kohsuke.stapler.StaplerRequest.fromStaplerRequest2(req),
                                       org.kohsuke.stapler.StaplerResponse.fromStaplerResponse2(res),
                                       chart,
                                       config.getBuildStatWidth(),
                                       config.getBuildStatHeight());
    }
    
    public void doBuildHistory(StaplerRequest2 req, StaplerResponse2 res) throws ServletException, IOException {
    	Hudson.getInstance().checkPermission(getRequiredPermission());
    	
    	BuildHistorySearchCriteria searchCriteria = FromRequestObjectFactory.createBuildHistorySearchCriteria(req);
    	
    	List<JobBuildSearchResult> filteredJobBuildResults = business.searchBuilds(searchCriteria);
    	
        req.setAttribute("jobResults", filteredJobBuildResults);
        req.setAttribute("searchCriteria", searchCriteria);
    	req.getView(this, "/hudson/plugins/global_build_stats/GlobalBuildStatsPlugin/buildHistory.jelly").forward(req, res);
    }
    
    @RequirePOST
    public void doUpdateBuildStatConfiguration(StaplerRequest2 req, StaplerResponse2 res) throws ServletException, IOException {
    	Hudson.getInstance().checkPermission(getRequiredPermission());
    	
    	boolean regenerateId = Boolean.parseBoolean(req.getParameter("regenerateId"));
    	
    	BuildStatConfiguration config = FromRequestObjectFactory.createBuildStatConfiguration(req.getParameter("buildStatId"), req);
    	business.updateBuildStatConfiguration(req.getParameter("buildStatId"), config, regenerateId);
    	
    	String json = JSONObject.fromObject(config).toString();
    	res.setContentType(CONTENT_TYPE);
    	res.getWriter().write(json);
    }
    
    @RequirePOST
    public void doAddBuildStatConfiguration(StaplerRequest2 req, StaplerResponse2 res) throws ServletException, IOException {
    	Hudson.getInstance().checkPermission(getRequiredPermission());
    	
    	BuildStatConfiguration config = FromRequestObjectFactory.createBuildStatConfiguration(ModelIdGenerator.INSTANCE.generateIdForClass(BuildStatConfiguration.class), req);
    	business.addBuildStatConfiguration(config);
    	
    	String json = JSONObject.fromObject(config).toString();
    	res.setContentType(CONTENT_TYPE);
    	res.getWriter().write(json);
    }
    
    @RequirePOST
    public void doDeleteConfiguration(StaplerRequest2 req, StaplerResponse2 res) throws ServletException, IOException {
    	Hudson.getInstance().checkPermission(getRequiredPermission());
    	
    	business.deleteBuildStatConfiguration(req.getParameter("buildStatId"));
    	
        respondAjaxOk(res);
    }
    
    @RequirePOST
    public void doMoveUpConf(StaplerRequest2 req, StaplerResponse2 res) throws ServletException, IOException {
    	Hudson.getInstance().checkPermission(getRequiredPermission());
    	
    	business.moveUpConf(req.getParameter("buildStatId"));
    	
        respondAjaxOk(res);
    }
    
    @RequirePOST
    public void doMoveDownConf(StaplerRequest2 req, StaplerResponse2 res) throws ServletException, IOException {
    	Hudson.getInstance().checkPermission(getRequiredPermission());
    	
    	business.moveDownConf(req.getParameter("buildStatId"));

        respondAjaxOk(res);
    }

    @RequirePOST
    public void doUpdateRetentionStrategies(StaplerRequest2 req, StaplerResponse2 res) throws ServletException, IOException {
        Hudson.getInstance().checkPermission(getRequiredPermission());

        List<RetentionStrategy> selectedStrategies = new ArrayList<RetentionStrategy>();
        if(req.getParameterValues("retentionStrategies") != null){
            for(String selectedStrategyId : req.getParameterValues("retentionStrategies")){
                RetentionStrategy retentionStrategy = RetentionStrategy.valueOf(selectedStrategyId);
                retentionStrategy.updateState(req.getParameterMap());
                selectedStrategies.add(retentionStrategy);
            }
        }

        business.updateRetentionStrategies(selectedStrategies);

        respondAjaxOk(res);
    }

    protected static void respondAjaxOk(StaplerResponse2 res) throws IOException {
        res.getWriter().write("{ status : 'ok' }");
    }
    
    /**
     * Method must stay here since, for an unknown reason, in buildHistory.jelly,
     * call to &lt;j:invokeStatic&gt; doesn't work (and &lt;j:invoke&gt; work fine !)
     * @param value Parameter which should be escaped
     * @return value where "\" are escaped
     */
	public static String escapeAntiSlashes(String value){
		return GlobalBuildStatsBusiness.escapeAntiSlashes(value);
	}
	
	/**
	 * For some unknown reasons, &lt;j:getStatic&gt; doesn't work due to a classloader problem (FieldFilterFactory doesn't seem
	 * to be accessible in a static way from jelly script)
	 * @return FieldFilterFactory.ALL_VALUES_FILTER_LABEL
	 */
	public static String getFieldFilterALL(){
		return FieldFilterFactory.ALL_VALUES_FILTER_LABEL;
	}
	
	/**
	 * For some unknown reasons, &lt;:getStatic&gt; doesn't work due to a classloader problem (FieldFilterFactory doesn't seem
	 * to be accessible in a static way from jelly script)
	 * @return FieldFilterFactory.REGEX_FIELD_FILTER_LABEL
	 */
	public static String getFieldFilterRegex(){
		return FieldFilterFactory.REGEX_FIELD_FILTER_LABEL;
	}
	
	public BuildStatConfiguration[] getBuildStatConfigsArrayed() {
		return buildStatConfigs.toArray(new BuildStatConfiguration[]{});
	}
	
	@Exported
	public List<BuildStatConfiguration> getBuildStatConfigs() {
		return buildStatConfigs;
	}
	
	public Permission getRequiredPermission(){
		return Hudson.ADMINISTER;
	}
	
	public HistoricScale[] getHistoricScales(){
		return HistoricScale.values();
	}
	
	public YAxisChartType[] getYAxisChartTypes(){
		return YAxisChartType.values();
	}

    /**
     * @return An unmodifiable list of job build results
     */
	public List<JobBuildResult> getJobBuildResults() {
        return this.jobBuildResultsSharder.getJobBuildResults();
	}

    public JobBuildResultSharder getJobBuildResultsSharder() {
        return jobBuildResultsSharder;
    }

    public void reloadJobBuildResults(List<JobBuildResult> results) {
        this.jobBuildResultsSharder = new JobBuildResultSharder(this.jobBuildResultsSharder, results);
    }

    public List<RetentionStrategy> getAvailableRetentionStrategies(){
        return RetentionStrategy.values();
    }

    public boolean isStrategySelected(String strategyId){
        return retentionStrategies.contains(RetentionStrategy.valueOf(strategyId));
    }

    public void setRetentionStrategies(List<RetentionStrategy> retentionStrategies) {
        this.retentionStrategies = retentionStrategies;
    }

    public List<RetentionStrategy> getRetentionStrategies() {
        return retentionStrategies;
    }
}
