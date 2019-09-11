package edu.uchicago.zhao.StormTopology;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.util.Bytes;

import backtype.storm.LocalCluster;
import backtype.storm.StormSubmitter;
import backtype.storm.generated.AlreadyAliveException;
import backtype.storm.generated.AuthorizationException;
import backtype.storm.generated.InvalidTopologyException;
import backtype.storm.spout.SchemeAsMultiScheme;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.BasicOutputCollector;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.TopologyBuilder;
import backtype.storm.topology.base.BaseBasicBolt;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;
import backtype.storm.utils.Utils;
import storm.kafka.KafkaSpout;
import storm.kafka.SpoutConfig;
import storm.kafka.StringScheme;
import storm.kafka.ZkHosts;

public class CrimeTopology {

	static class FilterCrimeBolt extends BaseBasicBolt {
		Pattern communityPattern;
		Pattern typePattern;
		@Override
		public void prepare(Map stormConf, TopologyContext context) {
			communityPattern = Pattern.compile("<community>K([A-Z]{3})</community>");
			typePattern = Pattern.compile("<type>([^<]*)</type>");
			super.prepare(stormConf, context);
		}

		@Override
		public void execute(Tuple tuple, BasicOutputCollector collector) {
			String report = tuple.getString(0);
			Matcher communityMatcher = communityPattern.matcher(report);
			if(!communityMatcher.find()) {
				return;
			}
			Matcher typeMatcher = typePattern.matcher(report);
			if(!typeMatcher.find()) {
				return;
			}
			collector.emit(new Values(communityMatcher.group(1), typeMatcher.group(1)));
		}

		@Override
		public void declareOutputFields(OutputFieldsDeclarer declarer) {
			declarer.declare(new Fields("community", "type"));
		}

	}

	static class ExtractCrimeBolt extends BaseBasicBolt {

		@Override
		public void execute(Tuple input, BasicOutputCollector collector) {
			String community = input.getStringByField("community");
			String type = input.getStringByField("type");
			collector.emit(new Values(community, type));
		}

		@Override
		public void declareOutputFields(OutputFieldsDeclarer declarer) {
			declarer.declare(new Fields("community", "type"));
		}

	}

	static class UpdateCurrentCrimeBolt extends BaseBasicBolt {
		private org.apache.hadoop.conf.Configuration conf;
		private Connection hbaseConnection;
		@Override
		public void prepare(Map stormConf, TopologyContext context) {
			try {
				conf = HBaseConfiguration.create();
			    conf.set("hbase.zookeeper.property.clientPort", "2181");
			    conf.set("hbase.zookeeper.quorum", StringUtils.join((List<String>)(stormConf.get("storm.zookeeper.servers")), ","));
			    String znParent = (String)stormConf.get("zookeeper.znode.parent");
			    if(znParent == null)
			    	znParent = new String("/hbase");
				conf.set("zookeeper.znode.parent", znParent);
				hbaseConnection = ConnectionFactory.createConnection(conf);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			super.prepare(stormConf, context);
		}

		@Override
		public void cleanup() {
			try {
				hbaseConnection.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			// TODO Auto-generated method stub
			super.cleanup();
		}

		@Override
		public void execute(Tuple input, BasicOutputCollector collector) {
			try {
				Table table = hbaseConnection.getTable(TableName.valueOf("terencezhao_current_crime"));
				Put put = new Put(Bytes.toBytes(input.getStringByField("community")));
				put.addColumn(Bytes.toBytes("crime"), Bytes.toBytes("type"), Bytes.toBytes(input.getStringByField("type")));
				table.put(put);
				table.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		@Override
		public void declareOutputFields(OutputFieldsDeclarer declarer) {
			// TODO Auto-generated method stub

		}

	}

	public static void main(String[] args) throws AlreadyAliveException, InvalidTopologyException, AuthorizationException {
		Map stormConf = Utils.readStormConfig();
		String zookeepers = StringUtils.join((List<String>)(stormConf.get("storm.zookeeper.servers")), ",");
		System.out.println(zookeepers);
		ZkHosts zkHosts = new ZkHosts(zookeepers);
		
		SpoutConfig kafkaConfig = new SpoutConfig(zkHosts, "terencezhao-crime-reports", "/terencezhao-crime-reports","crime_id");
		kafkaConfig.scheme = new SchemeAsMultiScheme(new StringScheme());
		// kafkaConfig.zkServers = (List<String>)stormConf.get("storm.zookeeper.servers");
		kafkaConfig.zkRoot = "/terencezhao-crime-reports";
		// kafkaConfig.zkPort = 2181;
		KafkaSpout kafkaSpout = new KafkaSpout(kafkaConfig);

		TopologyBuilder builder = new TopologyBuilder();

		builder.setSpout("raw-crime-reports", kafkaSpout, 1);
		builder.setBolt("filter-communities", new FilterCrimeBolt(), 1).shuffleGrouping("raw-crime-reports");
		builder.setBolt("extract-type", new ExtractCrimeBolt(), 1).shuffleGrouping("filter-communities");
		builder.setBolt("update-current-type", new UpdateCurrentCrimeBolt(), 1).fieldsGrouping("extract-type", new Fields("community"));


		Map conf = new HashMap();
		conf.put(backtype.storm.Config.TOPOLOGY_WORKERS, 4);

		if (args != null && args.length > 0) {
			StormSubmitter.submitTopology(args[0], conf, builder.createTopology());
		}   else {
			conf.put(backtype.storm.Config.TOPOLOGY_DEBUG, true);
			LocalCluster cluster = new LocalCluster(zookeepers, 2181L);
			cluster.submitTopology("terencezhao-crime-report-topology", conf, builder.createTopology());
		} 
	} 
}
