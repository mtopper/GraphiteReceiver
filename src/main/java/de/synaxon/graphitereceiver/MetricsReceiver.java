package de.synaxon.graphitereceiver;

import com.vmware.ee.statsfeeder.ExecutionContext;
import com.vmware.ee.statsfeeder.MOREFRetriever;
import com.vmware.ee.statsfeeder.PerfMetricSet;
import com.vmware.ee.statsfeeder.PerfMetricSet.PerfMetric;
import com.vmware.ee.statsfeeder.StatsExecutionContextAware;
import com.vmware.ee.statsfeeder.StatsFeederListener;
import com.vmware.ee.statsfeeder.StatsListReceiver;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;

/**
 * @author karl spies
 */
public class MetricsReceiver implements StatsListReceiver,
        StatsFeederListener, StatsExecutionContextAware {

    Log logger = LogFactory.getLog(MetricsReceiver.class);
    private boolean debugLogLevel = logger.isDebugEnabled();

    private String name = "SampleStatsReceiver";
    private String graphite_prefix = "vmware";
    //set to true for backwards compatibility
    private Boolean use_fqdn = true;
    //set to true for backwards compatibility
    private Boolean use_entity_type_prefix = false;
    private Boolean only_one_sample_x_period=true;

    private ExecutionContext context;
    private Socket client;
    PrintWriter out;
    private Properties props;
    private int freq;
    private MOREFRetriever ret;

    private int disconnectCounter = 0;
    private int disconnectAfter = -1;
    private boolean isResetConn = false;
    private long metricsCount = 0;
    private boolean isClusterHostMapInitialized = false;
    private Map clusterMap = Collections.EMPTY_MAP;
    private int cacheRefreshInterval = -1;
    private Date cacheRefreshStartTime = null;

    private boolean place_rollup_in_the_end;
    /**
     * This constructor will be called by StatsFeeder to load this receiver. The props object passed is built
     * from the content in the XML configuration for the receiver.
     * <pre>
     *    {@code
     *    <receiver>
     *      <name>sample</name>
     *      <class>com.vmware.ee.statsfeeder.SampleStatsReceiver</class>
     *      <!-- If you need some properties specify them like this
     *      <properties>
     *          <property>
     *              <name>some_property</name>
     *              <value>some_value</value>
     *          </property>
     *      </properties>
     *      -->
     *    </receiver>
     *    }
     * </pre>
     *
     * @param name receiver name
     * @param props properties
     */
    public MetricsReceiver(String name, Properties props) {
        this.name = name;
        this.props = props;
        logger.debug("MetricsReceiver Constructor.");
    }

    /**
     *
     * @return receiver name
     */
    public String getName() {
        this.logger.debug("MetricsReceiver getName: " + this.name);
        return name;
    }

    /**
     * This method is called when the receiver is initialized and passes the StatsFeeder execution context
     * which can be used to look up properties or other configuration data.
     *
     * It can also be used to retrieve the vCenter connection information
     *
     * @param context - The current execution context
     */
    @Override
    public void setExecutionContext(ExecutionContext context) {

        logger.debug("MetricsReceiver in setExecutionContext.");
        this.context = context;
        this.ret=context.getMorefRetriever();
        this.freq=context.getConfiguration().getFrequencyInSeconds();

        String prefix=this.props.getProperty("prefix");
        String use_fqdn=this.props.getProperty("use_fqdn");
        String use_entity_type_prefix=this.props.getProperty("use_entity_type_prefix");
        String only_one_sample_x_period=this.props.getProperty("only_one_sample_x_period");

        if(prefix != null && !prefix.isEmpty())
            this.graphite_prefix=prefix;

        if(use_fqdn != null && !use_fqdn.isEmpty())
            this.use_fqdn=Boolean.valueOf(use_fqdn);

        if(use_entity_type_prefix != null && !use_entity_type_prefix.isEmpty())
            this.use_entity_type_prefix=Boolean.valueOf(use_entity_type_prefix);

        if(only_one_sample_x_period!= null && !only_one_sample_x_period.isEmpty())
            this.only_one_sample_x_period=Boolean.valueOf(only_one_sample_x_period);

        if(this.props.getProperty("place_rollup_in_the_end") != null && !this.props.getProperty("place_rollup_in_the_end").isEmpty()) {
            this.place_rollup_in_the_end = Boolean.valueOf(this.props.getProperty("place_rollup_in_the_end"));
        } else {
            this.place_rollup_in_the_end = false;
        }

        try{
            this.disconnectAfter = Integer.parseInt(this.props.getProperty("graphite_force_reconnect_timeout"));
            if(this.disconnectAfter < 1){
                logger.info("if graphite_force_reconnect_timeout is set to < 1 will not be supported: " + this.disconnectAfter);
                this.disconnectAfter = -1;
            }else{
                logger.info("In setExecutionContext:: disconnectCounter and disconnectAfter Values: " + this.disconnectCounter + "\t" + this.disconnectAfter);
            }
        }catch(Exception e){
            logger.debug("graphite_force_reconnect_timeout attribute is not set or not supported.");
            logger.debug("graphite_force_reconnect_timeout is set to < 1 will not be supported: ");
            this.disconnectAfter = -1;
        }

        this.clusterMap = new HashMap();

        try{
            this.cacheRefreshInterval = Integer.parseInt(this.props.getProperty("cluster_map_refresh_timeout"));
            if(this.cacheRefreshInterval < 1){
                logger.info("if cluster_map_refresh_timeout is set to < 1 will not be supported: " + this.cacheRefreshInterval);
                this.cacheRefreshInterval = -1;
            }else{
                logger.info("setExecutionContext:: cluster_map_refresh_timeout Value: " + this.cacheRefreshInterval);
            }
        }catch(Exception e){
            logger.debug("cluster_map_refresh_timeout attribute is not set or not supported.");
            logger.debug("cluster_map_refresh_timeout is set to < 1 will not be supported: ");
            this.cacheRefreshInterval = -1;
        }
    }

    /**
     * getCluster returns associated cluster to the calling method. If requested VM/ESX managed entity does not exist in the cache,
     * it refreshes the cache.
     *
     * Potential Bottlenecks: If too many new VirtualMachines/ESX hosts added during runtime (between cache refresh intervals - this.cacheRefreshInterval)
     *                        may affect the performance because of too many vCenter connections and cache refreshments. We have tested & verified
     */
    private String getCluster(String entity){
        try{
            String value = (String)this.clusterMap.get(entity);
            if(value == null){
                logger.warn("Cluster Not Found for Managed Entity " + entity);
                logger.warn("Reinitializing Cluster Entity Map");
                Utils.initClusterHostMap(null, null, this.context, this.clusterMap);
                value = (String)this.clusterMap.get(entity);
            }
            return value;
        }catch(Exception e){
            return null;
        }
    }

    private void sendAllMetrics(String node,PerfMetricSet metricSet){
        final DateFormat SDF = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss'Z'");
        SDF.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            Iterator<PerfMetric> metrics = metricSet.getMetrics();
            while (metrics.hasNext()) {
                if((this.disconnectAfter > 0) && (++this.disconnectCounter >= this.disconnectAfter)){
                    logger.debug("sendAllMetrics - PerfMetric Counter Value: " + this.disconnectCounter);
                    this.resetGraphiteConnection();
                }
                PerfMetric sample = metrics.next();
                out.printf("%s %s %s%n", node, sample.getValue(), SDF.parse(sample.getTimestamp()).getTime() / 1000);

                if(this.debugLogLevel){
                    String str = String.format("%s %s %s%n", node, sample.getValue(), SDF.parse(sample.getTimestamp()).getTime() / 1000);
                    logger.debug("Graphite Output: " + str);
                }
            }
        } catch (Throwable t) {
            logger.error("Error processing entity stats on metric: "+node, t);
        }
    }

    private void sendMetricsAverage(String node,PerfMetricSet metricSet,int n){
        //averaging all values with last timestamp
        final DateFormat SDF = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss'Z'");
        SDF.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            double value;
            Iterator<PerfMetric> metrics = metricSet.getMetrics();
            //sample initialization
            PerfMetric sample=metrics.next();
            value=Double.valueOf(sample.getValue());
            while (metrics.hasNext()) {
                sample = metrics.next();
                value+=Double.valueOf(sample.getValue());
            }
            if((this.disconnectAfter > 0) && (++this.disconnectCounter >= this.disconnectAfter)){
                logger.debug("sendMetricsAverage - PerfMetric Counter Value: " + this.disconnectCounter);
                this.resetGraphiteConnection();
            }
            out.printf("%s %f %s%n", node, value/n, SDF.parse(sample.getTimestamp()).getTime() / 1000);

            if(this.debugLogLevel){
                String str = String.format("%s %f %s%n", node, value/n, SDF.parse(sample.getTimestamp()).getTime() / 1000);
                logger.debug("Graphite Output Average: " + str);
            }
        } catch (NumberFormatException t) {
            logger.error("Error on number format on metric: "+node, t);
        } catch (ParseException t) {
            logger.error("Error processing entity stats on metric: "+node, t);
        }
    }

    private void sendMetricsLatest(String node,PerfMetricSet metricSet) {
        final DateFormat SDF = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss'Z'");
        SDF.setTimeZone(TimeZone.getTimeZone("UTC"));
        try{
            //get last

            Iterator<PerfMetric> metrics = metricSet.getMetrics();
            PerfMetric sample=metrics.next();
            while (metrics.hasNext()) {
                sample = metrics.next();
            }
            if((this.disconnectAfter > 0) && (++this.disconnectCounter >= this.disconnectAfter)){
                logger.debug("sendMetricsLatest - PerfMetric Counter Value: " + this.disconnectCounter);
                this.resetGraphiteConnection();
            }
            out.printf("%s %s %s%n", node,sample.getValue() , SDF.parse(sample.getTimestamp()).getTime() / 1000);

            if(this.debugLogLevel){
                String str = String.format("%s %s %s%n", node,sample.getValue() , SDF.parse(sample.getTimestamp()).getTime() / 1000);
                logger.debug("Graphite Output Latest: " + str);
            }
        } catch (ParseException t) {
            logger.error("Error processing entity stats on metric: "+node, t);
        }
    }

    private void sendMetricsMaximum(String node,PerfMetricSet metricSet) {
        final DateFormat SDF = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss'Z'");
        SDF.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            double value;
            Iterator<PerfMetric> metrics = metricSet.getMetrics();
            //first value to compare
            PerfMetric sample=metrics.next();
            value=Double.valueOf(sample.getValue());
            //begin comparison iteration
            while (metrics.hasNext()) {
                sample = metrics.next();
                double last=Double.valueOf(sample.getValue());
                if(last > value) value=last;
            }
            if((this.disconnectAfter > 0) && (++this.disconnectCounter >= this.disconnectAfter)){
                logger.debug("sendMetricsMaximum - PerfMetric Counter Value: " + this.disconnectCounter);
                this.resetGraphiteConnection();
            }
            out.printf("%s %f %s%n", node, value, SDF.parse(sample.getTimestamp()).getTime() / 1000);

            if(this.debugLogLevel){
                String str = String.format("%s %f %s%n", node, value, SDF.parse(sample.getTimestamp()).getTime() / 1000);
                logger.debug("Graphite Output Maximum: " + str);
            }
        } catch (ParseException t) {
            logger.error("Error processing entity stats on metric: "+node, t);
        }
    }

    private void sendMetricsMinimim(String node,PerfMetricSet metricSet) {
        final DateFormat SDF = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss'Z'");
        SDF.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            //get minimum values with last timestamp
            double value;
            Iterator<PerfMetric> metrics = metricSet.getMetrics();
            //first value to compare
            PerfMetric sample=metrics.next();
            value=Double.valueOf(sample.getValue());
            //begin comparison iteration
            while (metrics.hasNext()) {
                sample = metrics.next();
                double last=Double.valueOf(sample.getValue());
                if(last < value) value=last;
            }
            if((this.disconnectAfter > 0) && (++this.disconnectCounter >= this.disconnectAfter)){
                logger.debug("sendMetricsMinimim - PerfMetric Counter Value: " + this.disconnectCounter);
                this.resetGraphiteConnection();
            }
            out.printf("%s %f %s%n", node, value, SDF.parse(sample.getTimestamp()).getTime() / 1000);

            if(this.debugLogLevel){
                String str = String.format("%s %f %s%n", node, value, SDF.parse(sample.getTimestamp()).getTime() / 1000);
                logger.debug("Graphite Output Minimum: " + str);
            }
        } catch (ParseException t) {
            logger.error("Error processing entity stats on metric: "+node, t);
        }
    }

    private void sendMetricsSummation(String node,PerfMetricSet metricSet){
        final DateFormat SDF = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss'Z'");
        SDF.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            //get minimum values with last timestamp
            double value;
            Iterator<PerfMetric> metrics = metricSet.getMetrics();
            //first value to compare
            PerfMetric sample=metrics.next();
            value=Double.valueOf(sample.getValue());
            //begin comparison iteration
            while (metrics.hasNext()) {
                sample = metrics.next();
                value+=Double.valueOf(sample.getValue());
            }
            if((this.disconnectAfter > 0) && (++this.disconnectCounter >= this.disconnectAfter)){
                logger.debug("sendMetricsSummation - PerfMetric Counter Value: " + this.disconnectCounter);
                this.resetGraphiteConnection();
            }
            out.printf("%s %f %s%n", node, value, SDF.parse(sample.getTimestamp()).getTime() / 1000);

            if(this.debugLogLevel){
                String str = String.format("%s %f %s%n", node, value, SDF.parse(sample.getTimestamp()).getTime() / 1000);
                logger.debug("Graphite Output Summation: " + str);
            }
        } catch (ParseException t) {
            logger.error("Error processing entity stats on metric: "+node, t);
        }
    }

    private String[] splitCounterName(String counterName) {
        //should split string in a 3 componet array
        // [0] = groupName
        // [1] = metricName
        // [2] = rollup
        String[] result=new String[3];
        String[] tmp=counterName.split("[.]");
        //group Name
        result[0]=tmp[0];
        //rollup
        result[2]=tmp[tmp.length-1];
        result[1]=tmp[1];
        if ( tmp.length > 3){
            for(int i=2;i<tmp.length-1;++i) {
                result[1]=result[1]+"."+tmp[i];
            }
        }
        return result;
    }

    /**
     * Main receiver entry point. This will be called for each entity and each metric which were retrieved by
     * StatsFeeder.
     *
     * receiveStats becomes a synchronized method for synchronizing all threads. Made this decision because PrintWriter low level Socket
     * APIs are not completely thread safe. We have observed runtime crashes if all threads call receiveStats method simultaneously.
     *
     * @param entityName - The name of the statsfeeder entity being retrieved
     * @param metricSet - The set of metrics retrieved for the entity
     */
    @Override
    public synchronized void receiveStats(String entityName, PerfMetricSet metricSet) {
        try {
            logger.debug("MetricsReceiver in receiveStats");

            if(!this.isClusterHostMapInitialized){
                if(this.cacheRefreshInterval != -1){
                    this.cacheRefreshStartTime = new Date();
                    logger.info("receiveStats cacheRefreshStartTime: " + cacheRefreshStartTime.toString());
                }
                this.isClusterHostMapInitialized = true;
                Utils.initClusterHostMap(null, null, this.context, this.clusterMap);
            }

            if (metricSet != null) {
                //-- Samples come with the following date format
                String node;
                String cluster = null;

                if((metricSet.getEntityName().contains("VirtualMachine")) ||
                        (metricSet.getEntityName().contains("HostSystem"))){

                    String myEntityName = ret.parseEntityName(metricSet.getEntityName());

                    if(myEntityName.equals("")){
                        logger.warn("Received Invalid Managed Entity. Failed to Continue.");
                        return;
                    }
                    myEntityName = myEntityName.replace(" ", "_");
                    cluster = this.getCluster(myEntityName);
                    if(cluster == null || cluster.equals("")){
                        logger.warn("Cluster Not Found for Entity " + myEntityName);
                        return;
                    }
                    logger.debug("Cluster and Entity: " + cluster + " : " + myEntityName);
                }

                String eName=null;
                String counterName=metricSet.getCounterName();
                //Get Instance Name
                String instanceName=metricSet.getInstanceId()
                        .replace('.','_')
                        .replace('-','_')
                        .replace('/','.')
                        .replace(' ','_');
                String statType=metricSet.getStatType();


                int interval=metricSet.getInterval();

                String rollup;

                if(use_entity_type_prefix) {
                    if(entityName.contains("[VirtualMachine]")) {
                        eName="vm."+ret.parseEntityName(entityName).replace('.', '_');
                    }else if (entityName.contains("[HostSystem]")) {
                        //for ESX only hostname
                        if(!use_fqdn) {
                            eName="esx."+ret.parseEntityName(entityName).split("[.]",2)[0];
                        }else {
                            eName="esx."+ret.parseEntityName(entityName).replace('.', '_');
                        }
                    }else if (entityName.contains("[Datastore]")) {
                        eName="dts."+ret.parseEntityName(entityName).replace('.', '_');
                    }else if (entityName.contains("[ResourcePool]")) {
                        eName="rp."+ret.parseEntityName(entityName).replace('.', '_');
                    }

                } else {
                    eName=ret.parseEntityName(entityName);

                    if(!use_fqdn && entityName.contains("[HostSystem]")){
                        eName=eName.split("[.]",2)[0];
                    }
                    eName=eName.replace('.', '_');
                }
                eName=eName.replace(' ','_').replace('-','_');
                logger.debug("Container Name :" +ret.getContainerName(eName) + " Interval: "+Integer.toString(interval)+ " Frequency :"+Integer.toString(freq));

                /*
                    Finally node contains these fields (depending on properties)
                    graphite_prefix.cluster.eName.groupName.instanceName.metricName_rollup_statType
                    graphite_prefix.cluster.eName.groupName.instanceName.metricName_statType_rollup

                    NOTES: if cluster is null cluster name disappears from node string.
                           if instanceName is null instanceName name disappears from node string.
                 */
                //Get group name (xxxx) metric name (yyyy) and rollup (zzzz)
                // from "xxxx.yyyyyy.xxxxx" on the metricName
                cluster = cluster.replace(".", "_");

                String[] counterInfo = splitCounterName(counterName);
                String groupName = counterInfo[0];
                String metricName = counterInfo[1];
                rollup = counterInfo[2];

                StringBuilder nodeBuilder = new StringBuilder();
                nodeBuilder.append(graphite_prefix).append(".");
                nodeBuilder.append((cluster == null || ("".equals(cluster))) ? "" : cluster + ".");
                nodeBuilder.append(eName).append(".");
                nodeBuilder.append(groupName).append(".");
                nodeBuilder.append((instanceName == null || ("".equals(instanceName))) ? "" : instanceName + ".");
                nodeBuilder.append(metricName).append("_");
                if(place_rollup_in_the_end){
                    nodeBuilder.append(statType).append("_");
                    nodeBuilder.append(rollup);
                } else {
                    nodeBuilder.append(rollup).append("_");
                    nodeBuilder.append(statType);
                }
                logger.debug((instanceName == null || ("".equals(instanceName))) ?
                                new StringBuilder("GP :").append(graphite_prefix).append(" EN: ").append(eName).append(" CN: ").append(counterName).append(" ST: ").append(statType).toString():
                                new StringBuilder("GP :").append(graphite_prefix).append(" EN: ").append(eName).append(" GN :").append(groupName).append(" IN :").append(instanceName).append(" MN :").append(metricName).append(" ST: ").append(statType).append(" RU: ").append(rollup).toString()
                );
                node = nodeBuilder.toString();
                metricsCount += metricSet.size();
                if(only_one_sample_x_period) {
                    logger.debug("one sample x period");
                    //check if metricSet has the expected number of metrics
                    int itv=metricSet.getInterval();
                    if(freq % itv != 0) {
                        logger.warn("frequency "+freq+ " is not multiple of interval: "+itv+ " at metric : "+node);
                        return;
                    }
                    int n=freq/itv;
                /* Noticed expected and received samples never match. I think we should check for whether received samples
                 * is the multiple of expected samples? Commenting here to move forward.
                if(n != metricSet.getValues().size()){
                    logger.error("ERROR: "+n+" expected samples but got "+metricSet.getValues().size()+ "at metric :"+node);
                    return;
                }
                */
                    if(rollup.equals("average")) {
                        sendMetricsAverage(node,metricSet,n);
                    } else if(rollup.equals("latest")) {
                        sendMetricsLatest(node,metricSet);
                    } else if(rollup.equals("maximum")) {
                        sendMetricsMaximum(node,metricSet);
                    } else if(rollup.equals("minimum")) {
                        sendMetricsMinimim(node,metricSet);
                    } else if(rollup.equals("summation")) {
                        sendMetricsSummation(node,metricSet);
                    } else {
                        logger.info("Not supported Rollup agration:"+rollup);
                    }
                } else {
                    logger.debug("all samples");
                    sendAllMetrics(node,metricSet);
                }
            } else {
                logger.debug("MetricsReceiver MetricSet is NULL");
            }
        } catch(Exception e){
            logger.fatal("Unexpected error occurred during metrics collection.", e);
        }
    }

    private void resetGraphiteConnection(){
        try {
            logger.debug("resetGraphiteConnection. Counter Value " + this.disconnectCounter + " Threshold Value of " + this.disconnectAfter + " reached. resetting Graphite Connection");
            isResetConn = true;
            this.onEndRetrieval();
            this.onStartRetrieval();
            isResetConn = false;
        }catch(Exception e){
            logger.fatal("Failed to Establish Graphite Server connection: " +
                    this.props.getProperty("host") + "\t" +
                    Integer.parseInt(this.props.getProperty("port", "2003")));
            System.exit(-1);
        }
    }

    /**
     * This method is guaranteed to be called at the start of each retrieval in single or feeder mode.
     * Receivers can place initialization code here that should be executed before retrieval is started.
     */
    @Override
    public void onStartRetrieval() {
        try {
            logger.debug("onStartRetrieval - Graphite Host and Port: " + this.props.getProperty("host") + "\t" + this.props.getProperty("port"));
            this.disconnectCounter = 0;

            if(isResetConn != true){
                metricsCount = 0;
                if(this.cacheRefreshStartTime != null){
                    Date cacheRefreshEndTime = new Date();
                    long refreshCacheTimeDiff = ((cacheRefreshEndTime.getTime()/1000) - (this.cacheRefreshStartTime.getTime()/1000));
                    if(refreshCacheTimeDiff >= this.cacheRefreshInterval){
                        this.isClusterHostMapInitialized = false;
                    }
                }
            }

            this.client = new Socket(
                    this.props.getProperty("host"),
                    Integer.parseInt(this.props.getProperty("port", "2003"))
            );
            OutputStream s = this.client.getOutputStream();
            this.out = new PrintWriter(s, true);
        } catch (IOException ex) {
            logger.error("Can't connect to graphite.", ex);
        }
    }

    /**
     * This method is guaranteed to be called, just once, at the end of each retrieval in single or feeder
     * mode. Receivers can place termination code here that should be executed after the retrieval is
     * completed.
     */
    @Override
    public void onEndRetrieval() {
        try {
            logger.debug("MetricsReceiver onEndRetrieval.");
            if(isResetConn != true){
                logger.info("onEndRetrieval PerformanceMetricsCountForEachRun: " + metricsCount);
            }

            this.out.close();
            this.client.close();

        } catch (IOException ex) {
            logger.error("Can't close resources.", ex);
        }
    }
}
