package com.hufudb.openhufu.owner.config;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import com.google.common.collect.ImmutableMap;
import com.hufudb.openhufu.data.schema.utils.PojoPublishedTableSchema;
import com.hufudb.openhufu.mpc.ProtocolExecutor;
import com.hufudb.openhufu.mpc.ProtocolFactory;
import com.hufudb.openhufu.mpc.ProtocolType;
import com.hufudb.openhufu.mpc.lib.LibraryConfig;
import com.hufudb.openhufu.mpc.lib.LibraryLoader;
import com.hufudb.openhufu.owner.adapter.Adapter;
import com.hufudb.openhufu.owner.adapter.AdapterConfig;
import com.hufudb.openhufu.owner.adapter.AdapterFactory;
import com.hufudb.openhufu.owner.adapter.AdapterLoader;
import com.hufudb.openhufu.proto.OpenHuFuService.OwnerInfo;
import com.hufudb.openhufu.rpc.grpc.OpenHuFuOwnerInfo;
import com.hufudb.openhufu.rpc.grpc.OpenHuFuRpc;
import io.grpc.TlsChannelCredentials;
import io.grpc.TlsServerCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OwnerConfigFile {
  private static final int THREAD_NUM = 8;
  public static final Logger LOG = LoggerFactory.getLogger(OwnerConfigFile.class);

  public int id;
  public int port;
  public int threadnum;
  public String hostname;
  public String privatekeypath;
  public String certchainpath;
  public String trustcertpath;
  public List<PojoPublishedTableSchema> tables;
  public AdapterConfig adapterconfig;
  public List<LibraryConfig> libraryconfigs;

  public OwnerConfigFile(int id, int port, int threadnum, String hostname, String privatekeypath,
      String certchainpath, String trustcertpath) {
    this.id = id;
    this.port = port;
    this.threadnum = threadnum;
    this.hostname = hostname;
    this.privatekeypath = privatekeypath;
    this.certchainpath = certchainpath;
    this.trustcertpath = trustcertpath;
  }

  public OwnerConfigFile() {}

  public static Adapter getAdapter(AdapterConfig config) {
    Path adapterDir = Paths.get(System.getenv("OPENHUFU_ROOT"), "adapter");
    Map<String, AdapterFactory> adapterFactories =
        AdapterLoader.loadAdapters(adapterDir.toString());
    AdapterFactory factory = adapterFactories.get(config.datasource);
    if (factory == null) {
      LOG.error("Fail to get adapter for datasource [{}]", config.datasource);
      throw new RuntimeException("Fail to get adapter for datasource");
    }
    return factory.create(config);
  }

  public Map<ProtocolType, ProtocolExecutor> getLibrary() {
    if (libraryconfigs == null || libraryconfigs.isEmpty()) {
      return ImmutableMap.of();
    }
    Path libDir = Paths.get(System.getenv("OPENHUFU_ROOT"), "lib");
    Map<ProtocolType, ProtocolFactory> factories =
        LibraryLoader.loadProtocolLibrary(libDir.toString());
    ImmutableMap.Builder<ProtocolType, ProtocolExecutor> libs = ImmutableMap.builder();
    for (LibraryConfig config : this.libraryconfigs) {
      try {
        switch (config.name.toLowerCase()) {
          case "aby":
            if (factories.containsKey(ProtocolType.ABY)) {
              OwnerInfo own = OwnerInfo.newBuilder()
                  .setEndpoint(String.format("%s:%d", hostname, config.port)).setId(id).build();
              libs.put(ProtocolType.ABY,
                  factories.get(ProtocolType.ABY).create(own, ProtocolType.ABY));
            } else {
              LOG.error("Library ABY not found");
            }
          default:
            LOG.error("Not support library {}", config.name);
        }
      } catch (UnsatisfiedLinkError e) {
        LOG.error("Fail to load library {}: {}", config.name, e.getMessage());
      }
    }
    return libs.build();
  }

  public OwnerConfig generateConfig() {
    OwnerConfig config = new OwnerConfig();
    config.party = new OpenHuFuOwnerInfo(id, String.format("%s:%d", hostname, port));
    config.port = port;
    config.hostname = hostname;
    if (threadnum > 0) {
      config.threadPool = Executors.newFixedThreadPool(threadnum);
    } else {
      config.threadPool = Executors.newFixedThreadPool(THREAD_NUM);
    }
    if (privatekeypath != null && certchainpath != null) {
      try {
        File certChain = new File(certchainpath);
        File privateKey = new File(privatekeypath);
        config.serverCerts = TlsServerCredentials.create(certChain, privateKey);
        config.useTLS = true;
        LOG.info("Load certChainFile and privateKeyFile");
      } catch (Exception e) {
        LOG.error("Fail to read certChainFile or privateKeyFile: {}", e.getMessage());
        config.useTLS = false;
      }
    }
    if (trustcertpath != null) {
      try {
        File rootCert = new File(trustcertpath);
        config.clientCerts = TlsChannelCredentials.newBuilder().trustManager(rootCert).build();
        config.acrossOwnerRpc = new OpenHuFuRpc(config.party, config.threadPool, config.clientCerts);
        LOG.info("load trustcertFile");
      } catch (Exception e) {
        LOG.error("Fail to read trustcertFile: {}", e.getMessage());
        config.acrossOwnerRpc = new OpenHuFuRpc(config.party, config.threadPool);
      }
    } else {
      config.acrossOwnerRpc = new OpenHuFuRpc(config.party, config.threadPool);
    }
    config.adapter = getAdapter(adapterconfig);
    config.librarys = getLibrary();
    config.tables = tables;
    return config;
  }
}
