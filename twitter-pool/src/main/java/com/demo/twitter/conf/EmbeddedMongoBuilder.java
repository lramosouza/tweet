package com.demo.twitter.conf;

import static de.flapdoodle.embed.mongo.distribution.Version.Main.PRODUCTION;

import java.io.IOException;
import java.net.InetAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.MongoClient;

import cz.jirutka.spring.embedmongo.slf4j.Slf4jLevel;
import cz.jirutka.spring.embedmongo.slf4j.Slf4jProgressListener;
import cz.jirutka.spring.embedmongo.slf4j.Slf4jStreamProcessor;
import de.flapdoodle.embed.mongo.Command;
import de.flapdoodle.embed.mongo.MongodExecutable;
import de.flapdoodle.embed.mongo.MongodProcess;
import de.flapdoodle.embed.mongo.MongodStarter;
import de.flapdoodle.embed.mongo.config.ArtifactStoreBuilder;
import de.flapdoodle.embed.mongo.config.DownloadConfigBuilder;
import de.flapdoodle.embed.mongo.config.IMongodConfig;
import de.flapdoodle.embed.mongo.config.MongodConfigBuilder;
import de.flapdoodle.embed.mongo.config.Net;
import de.flapdoodle.embed.mongo.config.RuntimeConfigBuilder;
import de.flapdoodle.embed.mongo.distribution.IFeatureAwareVersion;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.mongo.distribution.Versions;
import de.flapdoodle.embed.process.config.IRuntimeConfig;
import de.flapdoodle.embed.process.config.io.ProcessOutput;
import de.flapdoodle.embed.process.distribution.GenericVersion;
import de.flapdoodle.embed.process.runtime.Network;
import de.flapdoodle.embed.process.store.Downloader;
import de.flapdoodle.embed.process.store.IArtifactStore;

/**
 * A convenient builder for EmbedMongo that builds {@code MongodStarter} and
 * {@code MongodExecutable} with Slf4j as a logger (instead of JUL), then starts
 * "embedded" MongoDB and exposes preconfigured instance of {@link MongoClient}.
 * Due to Slf4j you use any logging implementation you want.
 *
 * <p>
 * To tweak logging levels use these classes as logger names:
 * <ul>
 * <li>de.flapdoodle.embed.mongo.MongodProcess</li>
 * <li>de.flapdoodle.embed.process.store.Downloader</li>
 * <li>cz.jirutka.spring.embedmongo.EmbeddedMongoFactoryBean</li>
 * </ul>
 * </p>
 *
 * <p>
 * EmbedMongo runs MongoDB as a managed process. It is not truly embedded Mongo
 * as there's no Java implementation of the MongoDB. EmbedMongo actually
 * downloads an original MongoDB binary for your platform and executes it. The
 * EmbedMongo process is stopped automatically when you close connection with
 * {@link com.mongodb.Mongo}.
 * </p>
 *
 * @see <a
 *      href=https://github.com/flapdoodle-oss/embedmongo.flapdoodle.de>embedmongo.flapdoodle.de</a>
 *
 * @since 1.2
 * @author Jakub Jirutka <jakub@jirutka.cz>
 */
public class EmbeddedMongoBuilder {
	private static final Logger LOG = LoggerFactory.getLogger(EmbeddedMongoBuilder.class);
	private IFeatureAwareVersion version = PRODUCTION;
	private Integer port;
	private String bindIp = InetAddress.getLoopbackAddress().getHostAddress();

	/**
	 * Builds {@link MongodStarter}, then starts "embedded" MongoDB instance and
	 * returns initialized {@code MongoClient}.
	 *
	 * <p>
	 * You should invoke {@link com.mongodb.Mongo#close()} after job is done to
	 * close the client and stop the MongoDB instance.
	 * </p>
	 *
	 * @return A fully initialized {@code MongoClient). @throws IOException
	 */
	public MongoClient build() throws IOException {
		LOG.info("Initializing embedded MongoDB instance");
		MongodStarter runtime = MongodStarter.getInstance(buildRuntimeConfig());
		MongodExecutable mongodExe = runtime.prepare(buildMongodConfig());
		LOG.info("Starting embedded MongoDB instance");
		mongodExe.start();
		return new MongoClient(bindIp, getPort());
	}

	/**
	 * The version of MongoDB to run. When no version is provided, then
	 * {@link Version.Main#PRODUCTION PRODUCTION} is used by default. The value must
	 * not be null.
	 */
	public EmbeddedMongoBuilder version(Version version) {
		if (version == null) {
			throw new IllegalArgumentException("Version must not be null");
		}
		this.version = version;
		return this;
	}

	/**
	 * The version of MongoDB to run e.g. 2.1.1, 1.6 v1.8.2, V2_0_4. When no version
	 * is provided, then {@link Version.Main#PRODUCTION PRODUCTION} is used by
	 * default. The value must not be empty.
	 */
	public EmbeddedMongoBuilder version(String version) {
		if (version == null || version.isEmpty()) {
			throw new IllegalArgumentException("Version must not be null or empty");
		}
		this.version = parseVersion(version);
		return this;
	}

	/**
	 * The port MongoDB should run on. When no port is provided, then some free
	 * server port is automatically assigned. The value must be between 0 and 65535.
	 */
	public EmbeddedMongoBuilder port(int port) {
		if (port <= 0 || port > 65535) {
			throw new IllegalArgumentException("Port number must be between 0 and 65535");
		}
		this.port = port;
		return this;
	}

	/**
	 * An IPv4 or IPv6 address for the MongoDB instance to be bound to during its
	 * execution. Default is a {@linkplain InetAddress#getLoopbackAddress() loopback
	 * address}. The value must not be empty.
	 */
	public EmbeddedMongoBuilder bindIp(String bindIp) {
		if (bindIp == null || bindIp.isEmpty()) {
			throw new IllegalArgumentException("BindIp must not be null or empty");
		}
		this.bindIp = bindIp;
		return this;
	}

	private int getPort() {
		if (port == null) {
			try {
				port = Network.getFreeServerPort();
			} catch (IOException ex) {
				LOG.error("Could not get free server port");
			}
		}
		return port;
	}

	private ProcessOutput buildOutputConfig() {
		Logger logger = LoggerFactory.getLogger(MongodProcess.class);
		return new ProcessOutput(new Slf4jStreamProcessor(logger, Slf4jLevel.TRACE),
				new Slf4jStreamProcessor(logger, Slf4jLevel.WARN), new Slf4jStreamProcessor(logger, Slf4jLevel.INFO));
	}

	private IRuntimeConfig buildRuntimeConfig() {
		return new RuntimeConfigBuilder().defaults(Command.MongoD).processOutput(buildOutputConfig())
				.artifactStore(buildArtifactStore()).build();
	}

	private IArtifactStore buildArtifactStore() {
		Logger logger = LoggerFactory.getLogger(Downloader.class);
		return new ArtifactStoreBuilder().defaults(Command.MongoD).download(new DownloadConfigBuilder()
				.defaultsForCommand(Command.MongoD).progressListener(new Slf4jProgressListener(logger)).build())
				.build();
	}

	private IMongodConfig buildMongodConfig() throws IOException {
		return new MongodConfigBuilder().version(version).net(new Net(bindIp, getPort(), Network.localhostIsIPv6()))
				.build();
	}

	private IFeatureAwareVersion parseVersion(String version) {
		String versionEnumName = version.toUpperCase().replaceAll("\\.", "_");
		if (!versionEnumName.startsWith("V")) {
			versionEnumName = "V" + versionEnumName;
		}
		try {
			return Version.valueOf(versionEnumName);
		} catch (IllegalArgumentException ex) {
			LOG.warn("Unrecognised MongoDB version '{}', this might be a new version that we don't yet know about. "
					+ "Attempting download anyway...", version);
			return Versions.withFeatures(new GenericVersion(version));
		}
	}
}