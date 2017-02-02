package com.telerik.plugins.appbuilderci;

import hudson.*;
import hudson.model.*;
import hudson.util.FormValidation;
import hudson.util.Secret;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import net.sf.json.JSONObject;

import org.apache.commons.io.FileUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;
import org.springframework.util.StopWatch;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriBuilder;

import com.google.common.base.Joiner;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.Charset;
import java.nio.file.*;

public class TelerikAppBuilder extends Builder {

	private String applicationId;
	private Secret accessToken;
	private String configuration;

	// iOS Properties
	private BuildSettingsiOS buildSettingsiOS;
	private BuildSettingsAndroid buildSettingsAndroid;
	private boolean buildSettingsWP;

	// "DataBoundConstructor"
	@DataBoundConstructor
	public TelerikAppBuilder(String applicationId, Secret accessToken, String configuration,
			BuildSettingsiOS buildSettingsiOS, BuildSettingsAndroid buildSettingsAndroid, boolean buildSettingsWP) {
		this.applicationId = applicationId;
		this.accessToken = accessToken;
		this.configuration = configuration;

		this.buildSettingsiOS = buildSettingsiOS;
		this.buildSettingsAndroid = buildSettingsAndroid;
		this.buildSettingsWP = buildSettingsWP;
	}

	/**
	 * We'll use this from the <tt>config.jelly</tt>.
	 */

	public String getApplicationId() {
		return applicationId;
	}

	public Secret getAccessToken() {
		return accessToken;
	}

	public String getConfiguration() {
		return configuration;
	}

	public String getCodesigningIdentityiOS() {
		if (buildSettingsiOS == null) {
			return "";
		}
		return buildSettingsiOS.codesigningIdentity;
	}

	public String getMobileProvisionIdentifieriOS() {
		if (buildSettingsiOS == null) {
			return "";
		}
		return buildSettingsiOS.mobileProvisionIdentifier;
	}

	public boolean getShowBuildSettingsiOS() {
		if (buildSettingsiOS == null) {
			return false;
		}
		return !buildSettingsiOS.isEmpty();
	}

	public BuildSettingsiOS getBuildSettingsiOS() {
		return buildSettingsiOS;
	}

	public String getCodesigningIdentityAndroid() {
		if (buildSettingsAndroid == null) {
			return "";
		}
		return buildSettingsAndroid.codesigningIdentity;
	}

	public boolean getShowBuildSettingsAndroid() {
		return buildSettingsAndroid != null;
	}

	public BuildSettingsAndroid getBuildSettingsAndroid() {
		return buildSettingsAndroid;
	}

	public boolean isBuildSettingsWP() {
		return buildSettingsWP;
	}

	private String getServerBaseUrl() {
		return getDescriptor().getServerBaseURI();
	}

	private boolean validatePluginConfiguration() {
		if (applicationId == null || accessToken == null || applicationId.isEmpty()
				|| Secret.toString(accessToken).isEmpty()) {
			return false;
		}
		return true;
	}

	private Client getWebClient() {
		ClientConfig config = new DefaultClientConfig();
		Client client = Client.create(config);
		client.setChunkedEncodingSize(1024);
		return client;
	}

	@Override
	public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener)
			throws IOException, InterruptedException {

		PrintStream logger = listener.getLogger();

		// Get Workspace root directory
		FilePath projectRoot = build.getWorkspace();
		logger.println("projectRoot = " + projectRoot);

		if(projectRoot == null){
			logger.println("ProjectRoot not found!");
			return false;
		}

		PackageManager pm = new PackageManager(logger);
		Path packageFilePath = pm.createPackage(projectRoot.toString());

		if (packageFilePath != null && !validatePluginConfiguration()) {
			logger.println("Please configure Telerik AppBuilder CI Plugin");
			return false;
		}

		return this.uploadPackage(packageFilePath, logger) && this.build(projectRoot.toString(), logger);
	}

	private boolean uploadPackage(Path packageFilePath, PrintStream logger) throws IOException {
		final File file = packageFilePath.toFile();
		String contentDisposition = "attachment; filename=\"" + file.getName() + "\"";
		boolean isUploadSuccessful = false;
		InputStream fileInStream = null;
		Client client = null;
		try{
			fileInStream = new FileInputStream(file);
			client = getWebClient();
			ClientResponse uploadPacakgeResponse = client.resource(this.getServerBaseUrl())
					.path(String.format("apps/%s/projects/importProject/%s", this.applicationId, Constants.ProjectName))
					.accept(MediaType.APPLICATION_JSON)
					.type(MediaType.APPLICATION_OCTET_STREAM)
					.header("Content-Disposition", contentDisposition)
					.header("Authorization", "ApplicationToken " + Secret.toString(accessToken))
					.post(ClientResponse.class, fileInStream);

			isUploadSuccessful = uploadPacakgeResponse.getStatus() == 204;
			if (!isUploadSuccessful) {
				logger.println(String.format("Uploading, Response: %s", uploadPacakgeResponse.toString()));
				logger.println(String.format("Response Body: %s", uploadPacakgeResponse.getEntity(String.class)));
			}
		}
		finally{
			if(fileInStream != null){
				fileInStream.close();
			}
			if(client != null){
				client.destroy();
			}
		}
		return isUploadSuccessful;
	}

	private boolean build(String workspaceDir, PrintStream logger) throws IOException {
		JSONObject buildProperties = getBuildProperties();
		Client client = getWebClient();
		boolean isBuildSuccessful = false;
		try{
			logger.println(
					String.format("Start Building for %s Configuration: %s",
							buildProperties.getJSONObject("Properties").get("Platform"),
							buildProperties.getJSONObject("Properties").get("Configuration")));

			StopWatch watch = new StopWatch();
			watch.start();

			ClientResponse response = client.resource(this.getServerBaseUrl())
					.path(String.format("apps/%s/build/%s", this.applicationId, Constants.ProjectName))
					.accept(MediaType.APPLICATION_JSON)
					.type(MediaType.APPLICATION_JSON)
					.header("Authorization", "ApplicationToken " + Secret.toString(accessToken))
					.post(ClientResponse.class, buildProperties.toString());

			watch.stop();

			JSONObject buildResult = JSONObject.fromObject(response.getEntity(String.class));

			isBuildSuccessful = response.getStatus() == 200
					&& this.getBuildObject(buildResult).getString("Status").equalsIgnoreCase("Success");

			if (isBuildSuccessful) {
				this.printBuildResult(buildResult, logger);
				this.downloadBuildResults(buildResult, workspaceDir, logger);
			} else if (buildResult.containsKey("Message")) {
				logger.println(String.format("Error Message: %s", buildResult.getString("Message")));
			} else {
				logger.println(String.format("Error Message: %s", buildResult.toString()));
			}

			logger.println(String.format("Build finished for %s seconds", watch.getTotalTimeSeconds()));
		}
		finally{
			client.destroy();
		}
		return isBuildSuccessful;
	}

	private JSONObject getBuildObject(JSONObject buildResult) {
		return buildResult.getJSONObject("ResultsByTarget").getJSONObject("Build");
	}

	private boolean downloadBuildResults(JSONObject buildResult, String workspaceDir, final PrintStream logger)
			throws IOException {
		final JSONObject projectProperties = getProjectProperties(workspaceDir);
		final Path outputFolderPath = Paths.get(workspaceDir, Constants.OutputFolderName).toAbsolutePath();
		if (outputFolderPath.toFile().exists()) {
			FileUtils.deleteDirectory(outputFolderPath.toFile());
		}
		Files.createDirectory(outputFolderPath);

		Object[] buildResultItems = this.getBuildObject(buildResult).getJSONArray("Items").toArray();

		String fileName = projectProperties.getString("ProjectName");
		for(Object obj : buildResultItems)
		{
			JSONObject item = (JSONObject) obj;
			if (!"LocalPath".equals(item.getString("Format"))) {
				continue;
			}
			String itemUrl = item.getString("FullPath");
			String extension = item.getString("Extension");
			String pathFormat = item.getString("Format");
			String platform = item.getString("Platform");

			int querySymbolIndex = extension.indexOf("?");
			if (querySymbolIndex > 0) {
				extension = extension.substring(0, querySymbolIndex);
			}

			if (pathFormat.equalsIgnoreCase("LocalPath")) {
				itemUrl = UriBuilder.fromPath(TelerikAppBuilder.this.getServerBaseUrl())
									.path(String.format("apps/%s/files/%s/%s",
											TelerikAppBuilder.this.applicationId,
											Constants.ProjectName,
											itemUrl.replace('\\', '/')))
									.build()
									.toString();
			}

			TelerikAppBuilder.this.downloadAppPackage(outputFolderPath, itemUrl, fileName, extension, platform, logger);
		}

		return true;
	}

	private void downloadAppPackage(Path outputFolderPath, String itemUrl, String fileName, String extension, String platform, PrintStream logger) {
		Client client = getWebClient();
		ClientResponse response = client.resource(itemUrl)
				.accept(MediaType.APPLICATION_OCTET_STREAM)
				.header("Authorization", "ApplicationToken " + Secret.toString(accessToken))
				.get(ClientResponse.class);

			logger.println(String.format("Request Status: %s, Platform: %s, Url: %s",
					response.getStatus(), platform, itemUrl.toString()));

			InputStream is = response.getEntityInputStream();
			if (is != null && response.getStatus() < 400) {
				Path destinationFilePath = Paths.get(outputFolderPath.toString(),
						String.format("%s%s", fileName, extension)).toAbsolutePath();
				File downloadFile = new File(destinationFilePath.toString());
				logger.println(String.format("Save File to %s", downloadFile.toPath()));
				try {
					Files.copy(is, downloadFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			else {
				logger.println(String.format("Response: %s", response.getEntity(String.class)));
			}
	}

	private void printBuildResult(JSONObject buildResult, PrintStream logger) {
		logger.println("Build Output :");
		logger.println(buildResult.getString("Output"));
	}

	private JSONObject getBuildProperties() {
		JSONObject buildData = new JSONObject();
		List<String> platforms = new ArrayList<String>();

		if (this.buildSettingsiOS != null) {
			platforms.add("iOS");
			buildData.put("MobileProvisionIdentifier", this.buildSettingsiOS.mobileProvisionIdentifier);
			buildData.put("iOSCodesigningIdentity", this.buildSettingsiOS.codesigningIdentity);
		}

		if (this.buildSettingsAndroid != null) {
			platforms.add("Android");
			buildData.put("AndroidCodesigningIdentity", this.buildSettingsAndroid.codesigningIdentity);
		}

		if (this.buildSettingsWP) {
			platforms.add("WP8");
		}

		buildData.put("Platform", Joiner.on(",").join(platforms));
		buildData.put("Configuration", this.configuration);

		JSONObject properties = new JSONObject();
		properties.put("Properties", buildData);

		return properties;
	}

	private JSONObject getProjectProperties(String workspaceDir) throws IOException {
        Path projectDir = new ProjectFilesManager().getProjectDir(workspaceDir);
		Path projectFileName = Paths.get(projectDir.toString(), Constants.ABProjectFileName);
		List<String> fileLines = Files.readAllLines(projectFileName, Charset.defaultCharset());

		return JSONObject.fromObject(Joiner.on("").join(fileLines));
	}

	@Override
	public DescriptorImpl getDescriptor() {
		return (DescriptorImpl) super.getDescriptor();
	}

	@Extension
	public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

		private String serverBaseURI = "https://platform.telerik.com/appbuilder/api";

		public DescriptorImpl() {
			load();
		}

		public FormValidation doCheckName(@QueryParameter String value) throws IOException, ServletException {
			return FormValidation.ok();
		}

		public boolean isApplicable(Class<? extends AbstractProject> aClass) {
			return true;
		}

		public String getServerBaseURI() {
	        return this.serverBaseURI;
	    }

		public String getDisplayName() {
			return "Telerik AppBuilder Build Step";
		}

		@Override
		public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
			this.serverBaseURI = formData.getString("serverBaseURI");
			save();
			return super.configure(req, formData);
		}
	}
}
