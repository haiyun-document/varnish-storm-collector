package com.ml.storm.varnishlog.topologies;

import java.util.HashMap;
import java.util.Map;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.zookeeper.ZooKeeper;

import com.ml.storm.varnishlog.bolts.BackendCollector;
import com.ml.storm.varnishlog.bolts.BackendMessageFilter;
import com.ml.storm.varnishlog.bolts.ClientMessageFilter;
import com.ml.storm.varnishlog.bolts.LogCollector;
import com.ml.storm.varnishlog.bolts.VarnishHadoopSaver;
import com.ml.storm.varnishlog.bolts.VarnishLineParser;
import com.ml.storm.varnishlog.bolts.VarnishMessageCreator;
import com.ml.storm.varnishlog.spouts.VarnishReaderSpout;

import backtype.storm.Config;
import backtype.storm.LocalCluster;
import backtype.storm.StormSubmitter;
import backtype.storm.generated.AlreadyAliveException;
import backtype.storm.generated.InvalidTopologyException;
import backtype.storm.generated.Nimbus.Client;
import backtype.storm.generated.Nimbus.Iface;
import backtype.storm.topology.TopologyBuilder;
import backtype.storm.tuple.Fields;

public class Topology {

	public static void main(String[] args) throws AlreadyAliveException, InvalidTopologyException {
		TopologyBuilder topology = new TopologyBuilder();
		Map<String, String> hosts = new HashMap<String, String>();
		Config conf = new Config();
		
		int i=0;
		for(String str : args[i].split(",")){
			String[] h = str.split(":");
			hosts.put(h[0], h[1]);
		}
		conf.put("varnishHosts", hosts);
		conf.put("namenodeUrl", args[++i]);
		conf.put("hadoopPath", args[++i]);
		conf.put("intervalInMinutes", Long.parseLong(args[++i]));
		
		int spouts = checkValue(Math.max(hosts.size(),10));
		int parsers = checkValue(spouts);
		int filter = checkValue(parsers / 2);
		int collector = checkValue(hosts.size());
		int messageCreater = checkValue(collector / 2);
		int savers = checkValue(messageCreater);
		
		int total = (spouts+parsers+filter+collector+messageCreater+savers) / 3;
		
		int workers = Math.min(40, total);
		conf.setNumWorkers(workers);
		conf.setNumAckers(3);
		
		Logger.getLogger("httpclient").setLevel(Level.ERROR);
		topology.setSpout("varnishlog-spout", new VarnishReaderSpout(), spouts);
		
		topology.setBolt("varnish-line-parser", new VarnishLineParser(),parsers)
			.fieldsGrouping("varnishlog-spout", new Fields("host","session"));

		topology.setBolt("varnish-client-filter", new ClientMessageFilter(),filter)
		.fieldsGrouping("varnish-line-parser", new Fields("host","session"));
		
		topology.setBolt("varnish-backend-filter", new BackendMessageFilter(), filter)
			.fieldsGrouping("varnish-line-parser", new Fields("host","session"));
		
		
		topology.setBolt("varnish-backend-collector", new BackendCollector(),collector)
			.fieldsGrouping("varnish-backend-filter", new Fields("host","session"));
		
		topology.setBolt("varnish-log-collector", new LogCollector(),collector)
			.fieldsGrouping("varnish-backend-collector", new Fields("host","session"))
			.fieldsGrouping("varnish-client-filter", new Fields("host","session"));
			
		topology.setBolt("varnish-message-creator", new VarnishMessageCreator(),messageCreater)
			.shuffleGrouping("varnish-log-collector");
		
		topology.setBolt("varnish-hadoop-saver", new VarnishHadoopSaver(),savers)
			.shuffleGrouping("varnish-message-creator");
		
		StormSubmitter.submitTopology("varnish-log-collector",conf, topology.createTopology());
		
//		LocalCluster cluster = new LocalCluster();
//		cluster.submitTopology("test", conf, topology.createTopology());
		
		
	}

	private static int checkValue(int i) {
		return i>0?i:1;
	}
}
