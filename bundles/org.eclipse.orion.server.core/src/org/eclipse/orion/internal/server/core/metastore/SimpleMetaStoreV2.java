/*******************************************************************************
 * Copyright (c) 2013, 2014 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.orion.internal.server.core.metastore;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.orion.server.core.OrionConfiguration;
import org.eclipse.orion.server.core.ServerConstants;
import org.eclipse.orion.server.core.metastore.MetadataInfo;
import org.eclipse.orion.server.core.metastore.ProjectInfo;
import org.eclipse.orion.server.core.metastore.UserInfo;
import org.eclipse.orion.server.core.metastore.WorkspaceInfo;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A simple implementation of a {@code IMetaStore}. This is version two that was introduced with Orion 6.0.
 * <p>
 * The design of the meta store is simply a set of json metadata files and workspace folders:
 * <ul>
 * <li>Each user has their own folder with a user.json and a list of folders, each of which is a workspace.</li>
 * <li>Workspaces and projects are organized in folders in a hierarchy under the user they belong to.</li>
 * <li>A user has a user.json metadata file under the user folder.</li>
 * <li>A workspace has a workspace.json metadata file under the user folder.</li>
 * <li>A project has a project.json metadata file under the user folder.</li>
 * </ul>
 * </p>
 * @author Anthony Hunter
 *
 */
public class SimpleMetaStoreV2 extends SimpleMetaStore {

	public final static int VERSION = 6;

	// map of read write locks keyed by userId
	private Map<String, ReadWriteLock> lockMap = new ConcurrentHashMap<String, ReadWriteLock>();

	/**
	 * Create an instance of a SimpleMetaStore under the provided folder.
	 * @param rootLocation The root location for storing content and metadata on this server.
	 */
	public SimpleMetaStoreV2(File rootLocation) {
		super(rootLocation);
	}

	public void createProject(ProjectInfo projectInfo) throws CoreException {
		if (projectInfo.getWorkspaceId() == null) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.createProject: workspace id is null.", null));
		}
		if (projectInfo.getFullName() == null) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.createProject: project name is null.", null));
		}
		WorkspaceInfo workspaceInfo;
		try {
			workspaceInfo = readWorkspace(projectInfo.getWorkspaceId());
		} catch (CoreException exception) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.createProject: could not find workspace with id:" + projectInfo.getWorkspaceId() + ", workspace does not exist.", null));
		}
		if (workspaceInfo == null) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.createProject: could not find workspace with id:" + projectInfo.getWorkspaceId() + ", workspace does not exist.", null));
		}
		String userId = SimpleMetaStoreUtil.decodeUserIdFromWorkspaceId(projectInfo.getWorkspaceId());
		String encodedWorkspaceName = SimpleMetaStoreUtil.decodeWorkspaceNameFromWorkspaceId(projectInfo.getWorkspaceId());
		String projectId = SimpleMetaStoreUtil.encodeProjectIdFromProjectName(projectInfo.getFullName());
		projectInfo.setUniqueId(projectId);

		JSONObject jsonObject = new JSONObject();
		try {
			jsonObject.put(SimpleMetaStore.ORION_VERSION, VERSION);
			jsonObject.put("UniqueId", projectInfo.getUniqueId());
			jsonObject.put("WorkspaceId", projectInfo.getWorkspaceId());
			jsonObject.put("FullName", projectInfo.getFullName());
			jsonObject.put("ContentLocation", projectInfo.getContentLocation());
			JSONObject properties = updateProperties(jsonObject, projectInfo);
			jsonObject.put("Properties", properties);
		} catch (JSONException e) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.createProject: could not create project: " + projectInfo.getFullName() + " for user " + userId, e));
		}
		File userMetaFolder = SimpleMetaStoreUtil.readMetaUserFolder(getRootLocation(), userId);
		File workspaceMetaFolder = SimpleMetaStoreUtil.readMetaFolder(userMetaFolder, encodedWorkspaceName);
		if (!SimpleMetaStoreUtil.isMetaFolder(workspaceMetaFolder, projectId)) {
			// try to create the project folder if the folder is not linked
			if ((projectInfo.getContentLocation() == null || projectInfo.getProjectStore().equals(getDefaultContentLocation(projectInfo))) && !SimpleMetaStoreUtil.createMetaFolder(workspaceMetaFolder, projectId)) {
				throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.createProject: could not create project: " + projectInfo.getFullName() + " for user " + userId, null));
			}
		}
		if (!SimpleMetaStoreUtil.createMetaFile(userMetaFolder, projectId, jsonObject)) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.createProject: could not create project: " + projectInfo.getFullName() + " for user " + userId, null));
		}

		// Update the workspace with the new projectName
		List<String> newProjectNames = new ArrayList<String>();
		newProjectNames.addAll(workspaceInfo.getProjectNames());
		newProjectNames.add(projectInfo.getFullName());
		workspaceInfo.setProjectNames(newProjectNames);
		updateWorkspace(workspaceInfo);
	}

	public void createUser(UserInfo userInfo) throws CoreException {
		if (userInfo.getUserName() == null) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.createUser: could not create user, did not provide a userName", null));
		}
		String userId = userInfo.getUserName();
		ReadWriteLock lock = getLockForUser(userId);
		lock.writeLock().lock();
		try {
			userInfo.setUniqueId(userId);
			File userMetaFolder = SimpleMetaStoreUtil.readMetaUserFolder(getRootLocation(), userId);
			if (SimpleMetaStoreUtil.isMetaFolder(getRootLocation(), userId)) {
				throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.createUser: could not create user: " + userId + ", user already exists", null));
			}
			JSONObject jsonObject = new JSONObject();
			try {
				jsonObject.put(SimpleMetaStore.ORION_VERSION, VERSION);
				jsonObject.put("UniqueId", userId);
				jsonObject.put("UserName", userId);
				jsonObject.put("FullName", userInfo.getFullName());
				jsonObject.put("WorkspaceIds", new JSONArray());
				JSONObject properties = updateProperties(jsonObject, userInfo);
				jsonObject.put("Properties", properties);
			} catch (JSONException e) {
				throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.createUser: could not create user: " + userId, e));
			}
			if (!SimpleMetaStoreUtil.createMetaUserFolder(getRootLocation(), userId)) {
				throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.createUser: could not create user: " + userId, null));
			}
			if (SimpleMetaStoreUtil.isMetaFile(userMetaFolder, SimpleMetaStore.USER)) {
				// If the file already exists then update with the new contents
				if (!SimpleMetaStoreUtil.updateMetaFile(userMetaFolder, SimpleMetaStore.USER, jsonObject)) {
					throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.createUser: could not create user: " + userId, null));
				}
			} else {
				if (!SimpleMetaStoreUtil.createMetaFile(userMetaFolder, SimpleMetaStore.USER, jsonObject)) {
					throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.createUser: could not create user: " + userId, null));
				}
			}
			Logger logger = LoggerFactory.getLogger("org.eclipse.orion.server.config"); //$NON-NLS-1$
			logger.debug("Created new user " + userId + "."); //$NON-NLS-1$
		} finally {
			lock.writeLock().unlock();
		}
	}

	public void createWorkspace(WorkspaceInfo workspaceInfo) throws CoreException {
		if (workspaceInfo.getUserId() == null) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.createWorkspace: user id is null.", null));
		}
		if (workspaceInfo.getFullName() == null) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.createWorkspace: workspace name is null.", null));
		}
		UserInfo userInfo;
		try {
			userInfo = readUser(workspaceInfo.getUserId());
		} catch (CoreException exception) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.createWorkspace: could not find user with id: " + workspaceInfo.getUserId() + ", user does not exist.", null));
		}
		if (userInfo == null) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.createWorkspace: could not find user with id: " + workspaceInfo.getUserId() + ", user does not exist.", null));
		}
		// We create a meta folder for the workspace using the encoded workspace name
		String workspaceId = SimpleMetaStoreUtil.encodeWorkspaceId(workspaceInfo.getUserId(), workspaceInfo.getFullName());
		String encodedWorkspaceName = SimpleMetaStoreUtil.decodeWorkspaceNameFromWorkspaceId(workspaceId);
		// It is possible to have two workspaces with the same name, so append an integer if this is a duplicate name.
		File userMetaFolder = SimpleMetaStoreUtil.readMetaUserFolder(getRootLocation(), userInfo.getUniqueId());
		int suffix = 0;
		while (SimpleMetaStoreUtil.isMetaFolder(userMetaFolder, encodedWorkspaceName)) {
			suffix++;
			workspaceId = SimpleMetaStoreUtil.encodeWorkspaceId(workspaceInfo.getUserId(), workspaceInfo.getFullName() + suffix);
			encodedWorkspaceName = SimpleMetaStoreUtil.decodeWorkspaceNameFromWorkspaceId(workspaceId);
		}
		workspaceInfo.setUniqueId(workspaceId);
		JSONObject jsonObject = new JSONObject();
		try {
			jsonObject.put(SimpleMetaStore.ORION_VERSION, VERSION);
			jsonObject.put("UniqueId", workspaceInfo.getUniqueId());
			jsonObject.put("UserId", workspaceInfo.getUserId());
			jsonObject.put("FullName", workspaceInfo.getFullName());
			jsonObject.put("ProjectNames", new JSONArray());
			JSONObject properties = updateProperties(jsonObject, workspaceInfo);
			jsonObject.put("Properties", properties);
		} catch (JSONException e) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.createWorkspace: could not create workspace: " + encodedWorkspaceName + " for user " + userInfo.getUserName(), e));
		}
		if (!SimpleMetaStoreUtil.createMetaFolder(userMetaFolder, encodedWorkspaceName)) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.createWorkspace: could not create workspace: " + encodedWorkspaceName + " for user " + userInfo.getUserName(), null));
		}
		if (!SimpleMetaStoreUtil.createMetaFile(userMetaFolder, workspaceId, jsonObject)) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.createWorkspace: could not create workspace: " + encodedWorkspaceName + " for user " + userInfo.getUserName(), null));
		}

		// Update the user with the new workspaceId
		List<String> newWorkspaceIds = new ArrayList<String>();
		newWorkspaceIds.addAll(userInfo.getWorkspaceIds());
		newWorkspaceIds.add(workspaceId);
		userInfo.setWorkspaceIds(newWorkspaceIds);
		updateUser(userInfo);
	}

	public void deleteProject(String workspaceId, String projectName) throws CoreException {
		String userId = SimpleMetaStoreUtil.decodeUserIdFromWorkspaceId(workspaceId);
		String encodedWorkspaceName = SimpleMetaStoreUtil.decodeWorkspaceNameFromWorkspaceId(workspaceId);
		File userMetaFolder = SimpleMetaStoreUtil.readMetaUserFolder(getRootLocation(), userId);
		File workspaceMetaFolder = SimpleMetaStoreUtil.readMetaFolder(userMetaFolder, encodedWorkspaceName);

		// Update the workspace, remove the deleted projectName
		WorkspaceInfo workspaceInfo;
		try {
			workspaceInfo = readWorkspace(workspaceId);
		} catch (CoreException exception) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.deleteProject: could not find project with name:" + projectName + ", workspace does not exist.", null));
		}
		List<String> newProjectIds = new ArrayList<String>();
		newProjectIds.addAll(workspaceInfo.getProjectNames());
		newProjectIds.remove(projectName);
		workspaceInfo.setProjectNames(newProjectIds);
		updateWorkspace(workspaceInfo);

		String projectId = SimpleMetaStoreUtil.encodeProjectIdFromProjectName(projectName);
		if (!SimpleMetaStoreUtil.deleteMetaFile(userMetaFolder, projectId)) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.deleteProject: could not delete project: " + projectName + " for user " + userId, null));
		}
		if (SimpleMetaStoreUtil.isMetaFolder(workspaceMetaFolder, projectId)) {
			// delete the project folder since it still exists
			if (!SimpleMetaStoreUtil.deleteMetaFolder(workspaceMetaFolder, projectId, true)) {
				throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.deleteProject: could not delete project: " + projectName + " for user " + userId, null));
			}
		}
	}

	public void deleteUser(String userId) throws CoreException {
		ReadWriteLock lock = getLockForUser(userId);
		lock.writeLock().lock();
		try {
			UserInfo userInfo;
			try {
				userInfo = readUser(userId);
			} catch (CoreException exception) {
				throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.deleteUser: could not delete user with id: " + userId + ", user does not exist.", null));
			}

			// First delete the workspaces
			for (String workspaceId : userInfo.getWorkspaceIds()) {
				deleteWorkspace(userId, workspaceId);
			}

			File userMetaFolder = SimpleMetaStoreUtil.readMetaUserFolder(getRootLocation(), userId);
			if (!SimpleMetaStoreUtil.deleteMetaFile(userMetaFolder, SimpleMetaStore.USER)) {
				throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.deleteUser: could not delete user: " + userId, null));
			}
			if (!SimpleMetaStoreUtil.deleteMetaUserFolder(userMetaFolder, userId)) {
				throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.deleteUser: could not delete user: " + userId, null));
			}
		} finally {
			lock.writeLock().unlock();
		}
	}

	public void deleteWorkspace(String userId, String workspaceId) throws CoreException {
		String encodedWorkspaceName = SimpleMetaStoreUtil.decodeWorkspaceNameFromWorkspaceId(workspaceId);
		File userMetaFolder = SimpleMetaStoreUtil.readMetaUserFolder(getRootLocation(), userId);

		// Delete the projects in the workspace
		WorkspaceInfo workspaceInfo = readWorkspace(workspaceId);
		for (String projectName : workspaceInfo.getProjectNames()) {
			deleteProject(workspaceId, projectName);
		}

		// Update the user remove the deleted workspaceId
		UserInfo userInfo;
		try {
			userInfo = readUser(userId);
		} catch (CoreException exception) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.deleteWorkspace: could not find user with id:" + userId + ", user does not exist.", null));
		}
		List<String> newWorkspaceIds = new ArrayList<String>();
		newWorkspaceIds.addAll(userInfo.getWorkspaceIds());
		newWorkspaceIds.remove(workspaceId);
		userInfo.setWorkspaceIds(newWorkspaceIds);
		updateUser(userInfo);

		// delete the meta file and folder
		if (!SimpleMetaStoreUtil.deleteMetaFile(userMetaFolder, workspaceId)) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.deleteWorkspace: could not delete workspace: " + encodedWorkspaceName, null));
		}
		if (!SimpleMetaStoreUtil.deleteMetaFolder(userMetaFolder, encodedWorkspaceName, true)) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.deleteWorkspace: could not delete workspace: " + encodedWorkspaceName, null));
		}
	}

	public IFileStore getDefaultContentLocation(ProjectInfo projectInfo) throws CoreException {
		if (projectInfo.getWorkspaceId() == null) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.getDefaultContentLocation: workspace id is null.", null));
		}
		if (projectInfo.getFullName() == null) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.getDefaultContentLocation: project name is null.", null));
		}
		IFileStore workspaceFolder = getWorkspaceContentLocation(projectInfo.getWorkspaceId());
		String projectId = SimpleMetaStoreUtil.encodeProjectIdFromProjectName(projectInfo.getFullName());
		IFileStore projectFolder = workspaceFolder.getChild(projectId);
		return projectFolder;
	}

	private ReadWriteLock getLockForUser(String userId) {
		if (!lockMap.containsKey(userId)) {
			ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
			lockMap.put(userId, lock);
		}
		return lockMap.get(userId);
	}

	public IFileStore getUserHome(String userId) {
		IFileStore root = OrionConfiguration.getRootLocation();
		if (userId != null) {
			//the format of the user home is /serverworkspace/an/anthony
			String userPrefix = userId.substring(0, Math.min(2, userId.length()));
			return root.getChild(userPrefix).getChild(userId);
		}
		//for backwards compatibility, if userId is null, the old API used to return the root location;
		return root;
	}

	/**
	 * Gets the properties in the provided MetaDataInfo.
	 * @param metadataInfo The MetaData info
	 * @return the JSON with a list of properties.
	 * @throws JSONException
	 */
	private JSONObject getUserProperties(UserInfo userInfo) throws JSONException {
		JSONObject jsonObject = new JSONObject();
		Map<String, String> properties = userInfo.getProperties();
		for (String key : properties.keySet()) {
			String value = properties.get(key);
			if ("UserRights".equals(key)) {
				// UserRights needs to be handled specifically since it is a JSONArray and not a string.
				JSONArray userRights = new JSONArray(value);
				jsonObject.put("UserRights", userRights);
			} else if ("SiteConfigurations".equals(key)) {
				// UserRights needs to be handled specifically since it is a JSONObject and not a string.
				JSONObject siteConfigurations = new JSONObject(value);
				jsonObject.put("SiteConfigurations", siteConfigurations);
			} else {
				jsonObject.put(key, value);
			}
		}
		return jsonObject;
	}

	public IFileStore getWorkspaceContentLocation(String workspaceId) throws CoreException {
		if (workspaceId == null) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.getWorkspaceContentLocation: workspace id is null.", null));
		}
		String userId = SimpleMetaStoreUtil.decodeUserIdFromWorkspaceId(workspaceId);
		String encodedWorkspaceName = SimpleMetaStoreUtil.decodeWorkspaceNameFromWorkspaceId(workspaceId);
		File userMetaFolder = SimpleMetaStoreUtil.readMetaUserFolder(getRootLocation(), userId);
		File workspaceMetaFolder = SimpleMetaStoreUtil.readMetaFolder(userMetaFolder, encodedWorkspaceName);
		IFileStore userHome = getUserHome(userId);
		IFileStore workspaceFolder = userHome.getChild(workspaceMetaFolder.getName());
		return workspaceFolder;
	}

	/**
	 * Initialize the simple meta store.
	 * @param rootLocation The root location, a folder on the server.
	 */
	protected void initializeMetaStore(File rootLocation) {
		if (!SimpleMetaStoreUtil.isMetaFile(rootLocation, SimpleMetaStore.ROOT)) {
			// the root metastore.json file does not exist, see if migration is required
			// create a new root metastore.json file
			JSONObject jsonObject = new JSONObject();
			try {
				jsonObject.put(SimpleMetaStore.ORION_VERSION, VERSION);
			} catch (JSONException e) {
				throw new RuntimeException("SimpleMetaStore.initializeMetaStore: could not create MetaStore");
			}
			if (!SimpleMetaStoreUtil.createMetaFile(rootLocation, SimpleMetaStore.ROOT, jsonObject)) {
				throw new RuntimeException("SimpleMetaStore.initializeMetaStore: could not create MetaStore");
			}
			Logger logger = LoggerFactory.getLogger("org.eclipse.orion.server.config"); //$NON-NLS-1$
			logger.debug("Created new simple metadata store (version " + VERSION + ")."); //$NON-NLS-1$
		} else {
			// Verify we have a valid MetaStore with the right version
			JSONObject jsonObject = SimpleMetaStoreUtil.readMetaFile(rootLocation, SimpleMetaStore.ROOT);
			try {
				if (jsonObject == null || jsonObject.getInt(SimpleMetaStore.ORION_VERSION) != VERSION) {
					// Migration is required
					SimpleMetaStoreMigrationV2 migration = new SimpleMetaStoreMigrationV2();
					if (migration.isMigrationRequired(rootLocation)) {
						migration.doMigration(rootLocation);
					} else {
						throw new RuntimeException("SimpleMetaStore.initializeMetaStore: could not read MetaStore");
					}
				}
			} catch (JSONException e) {
				throw new RuntimeException("SimpleMetaStore.initializeMetaStore: could not read MetaStore.");
			}
		}

		Logger logger = LoggerFactory.getLogger("org.eclipse.orion.server.config"); //$NON-NLS-1$
		logger.info("Loaded simple metadata store (version " + VERSION + ")."); //$NON-NLS-1$
	}
	
	public List<String> readAllUsers() throws CoreException {
		return SimpleMetaStoreUtil.listMetaUserFolders(getRootLocation());
	}

	public ProjectInfo readProject(String workspaceId, String projectName) throws CoreException {
		String userId = SimpleMetaStoreUtil.decodeUserIdFromWorkspaceId(workspaceId);
		String encodedWorkspaceName = SimpleMetaStoreUtil.decodeWorkspaceNameFromWorkspaceId(workspaceId);
		if (userId == null || encodedWorkspaceName == null) {
			return null;
		}
		File userMetaFolder = SimpleMetaStoreUtil.readMetaUserFolder(getRootLocation(), userId);
		File workspaceMetaFolder = SimpleMetaStoreUtil.readMetaFolder(userMetaFolder, encodedWorkspaceName);
		if (workspaceMetaFolder == null) {
			return null;
		}
		String projectId = SimpleMetaStoreUtil.encodeProjectIdFromProjectName(projectName);
		JSONObject jsonObject = SimpleMetaStoreUtil.readMetaFile(userMetaFolder, projectId);
		ProjectInfo projectInfo = new ProjectInfo();
		if (jsonObject == null) {
			if (SimpleMetaStoreUtil.isMetaFolder(workspaceMetaFolder, projectId) && !SimpleMetaStoreUtil.isMetaFile(userMetaFolder, projectId)) {
				// the project folder exists but the project json file does not, so create it
				File projectMetaFolder = SimpleMetaStoreUtil.readMetaFolder(workspaceMetaFolder, projectId);
				URI projectLocation = projectMetaFolder.toURI();
				projectInfo.setFullName(projectName);
				projectInfo.setWorkspaceId(workspaceId);
				projectInfo.setContentLocation(projectLocation);
				createProject(projectInfo);
				jsonObject = SimpleMetaStoreUtil.readMetaFile(userMetaFolder, projectId);
			} else {
				// both the project folder and project json do not exist, no project
				// OR both the project folder and project json exist, but bad project JSON file == no project
				return null;
			}
		}
		try {
			projectInfo.setUniqueId(jsonObject.getString("UniqueId"));
			projectInfo.setWorkspaceId(jsonObject.getString("WorkspaceId"));
			projectInfo.setFullName(jsonObject.getString("FullName"));
			projectInfo.setContentLocation(new URI(jsonObject.getString("ContentLocation")));
			setProperties(projectInfo, jsonObject.getJSONObject("Properties"));
			projectInfo.flush();
		} catch (JSONException e) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.readProject: could not read project " + projectName + " for userId " + userId, e));
		} catch (URISyntaxException e) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.readProject: could not read project " + projectName + " for userId " + userId, e));
		}
		return projectInfo;
	}

	public UserInfo readUser(String userId) throws CoreException {
		ReadWriteLock lock = getLockForUser(userId);
		lock.readLock().lock();
		try {
			File userMetaFolder = SimpleMetaStoreUtil.readMetaUserFolder(getRootLocation(), userId);
			if (SimpleMetaStoreUtil.isMetaFile(userMetaFolder, SimpleMetaStore.USER)) {
				JSONObject jsonObject = SimpleMetaStoreUtil.readMetaFile(userMetaFolder, SimpleMetaStore.USER);
				if (jsonObject == null) {
					Logger logger = LoggerFactory.getLogger("org.eclipse.orion.server.config"); //$NON-NLS-1$
					logger.info("SimpleMetaStore.readUser: could not read user " + userId); //$NON-NLS-1$
					return null;
				}
				UserInfo userInfo = new UserInfo();
				try {
					userInfo.setUniqueId(jsonObject.getString("UniqueId"));
					userInfo.setUserName(jsonObject.getString("UserName"));
					if (jsonObject.isNull("FullName")) {
						userInfo.setFullName("Unnamed User");
					} else {
						userInfo.setFullName(jsonObject.getString("FullName"));
					}
					List<String> userWorkspaceIds = new ArrayList<String>();
					JSONArray workspaceIds = jsonObject.getJSONArray("WorkspaceIds");
					if (workspaceIds.length() > 0) {
						for (int i = 0; i < workspaceIds.length(); i++) {
							userWorkspaceIds.add(workspaceIds.getString(i));
						}
					}
					userInfo.setWorkspaceIds(userWorkspaceIds);
					setProperties(userInfo, jsonObject.getJSONObject("Properties"));
					userInfo.flush();
				} catch (JSONException e) {
					throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.readUser: could not read user " + userId, e));
				}
				return userInfo;
			}
		} finally {
			lock.readLock().unlock();
		}

		// user does not exist for this userId, create it ( see Bug 415505 )
		UserInfo userInfo = new UserInfo();
		userInfo.setUniqueId(userId);
		userInfo.setUserName(userId);
		userInfo.setFullName("Unnamed User");
		createUser(userInfo);
		return userInfo;
	}

	public WorkspaceInfo readWorkspace(String workspaceId) throws CoreException {
		if (workspaceId == null) {
			return null;
		}
		String userName = SimpleMetaStoreUtil.decodeUserIdFromWorkspaceId(workspaceId);
		String encodedWorkspaceName = SimpleMetaStoreUtil.decodeWorkspaceNameFromWorkspaceId(workspaceId);
		if (userName == null || encodedWorkspaceName == null) {
			// could not decode, so cannot find workspace
			return null;
		}
		if (!SimpleMetaStoreUtil.isMetaUserFolder(getRootLocation(), userName)) {
			return null;
		}
		File userMetaFolder = SimpleMetaStoreUtil.readMetaUserFolder(getRootLocation(), userName);
		if (!SimpleMetaStoreUtil.isMetaFolder(userMetaFolder, encodedWorkspaceName)) {
			return null;
		}
		JSONObject jsonObject = SimpleMetaStoreUtil.readMetaFile(userMetaFolder, workspaceId);
		if (jsonObject == null) {
			return null;
		}
		WorkspaceInfo workspaceInfo = new WorkspaceInfo();
		try {
			workspaceInfo.setUniqueId(jsonObject.getString("UniqueId"));
			workspaceInfo.setUserId(jsonObject.getString("UserId"));
			workspaceInfo.setFullName(jsonObject.getString("FullName"));
			List<String> workspaceProjectNames = new ArrayList<String>();
			JSONArray projectNames = jsonObject.getJSONArray("ProjectNames");
			if (projectNames.length() > 0) {
				for (int i = 0; i < projectNames.length(); i++) {
					workspaceProjectNames.add(projectNames.getString(i));
				}
			}
			workspaceInfo.setProjectNames(workspaceProjectNames);
			setProperties(workspaceInfo, jsonObject.getJSONObject("Properties"));
			workspaceInfo.flush();
		} catch (JSONException e) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.readWorkspace: could not read workspace " + encodedWorkspaceName + " for user id " + userName, e));
		}
		return workspaceInfo;
	}

	/**
	 * Sets all the properties in the provided JSON into the MetaDataInfo.
	 * @param metadataInfo The MetaData info
	 * @param jsonObject the JSON with a list of properties.
	 * @throws JSONException
	 */
	private void setProperties(MetadataInfo metadataInfo, JSONObject jsonObject) throws JSONException {
		String[] properties = JSONObject.getNames(jsonObject);
		if (properties != null) {
			for (String key : properties) {
				Object value = jsonObject.get(key);
				metadataInfo.setProperty(key, value.toString());
			}
		}
	}

	public void updateProject(ProjectInfo projectInfo) throws CoreException {
		String userId = SimpleMetaStoreUtil.decodeUserIdFromWorkspaceId(projectInfo.getWorkspaceId());
		String encodedWorkspaceName = SimpleMetaStoreUtil.decodeWorkspaceNameFromWorkspaceId(projectInfo.getWorkspaceId());
		File userMetaFolder = SimpleMetaStoreUtil.readMetaUserFolder(getRootLocation(), userId);
		File workspaceMetaFolder = SimpleMetaStoreUtil.readMetaFolder(userMetaFolder, encodedWorkspaceName);
		if (!SimpleMetaStoreUtil.isMetaFile(userMetaFolder, projectInfo.getUniqueId())) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.updateProject: could not update project: " + projectInfo.getFullName() + " for workspace " + encodedWorkspaceName, null));
		}
		JSONObject jsonObject = SimpleMetaStoreUtil.readMetaFile(userMetaFolder, projectInfo.getUniqueId());
		if (!projectInfo.getUniqueId().equals(SimpleMetaStoreUtil.encodeProjectIdFromProjectName(projectInfo.getFullName()))) {
			IFileStore projectStore = projectInfo.getProjectStore();
			IFileStore defaultProjectStore = getDefaultContentLocation(readProject(projectInfo.getWorkspaceId(), projectInfo.getUniqueId()));
			// full name has changed, this is a project move
			String newProjectId = SimpleMetaStoreUtil.encodeProjectIdFromProjectName(projectInfo.getFullName());
			if (!SimpleMetaStoreUtil.moveMetaFile(userMetaFolder, projectInfo.getUniqueId(), newProjectId)) {
				throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.updateProject: could not move project: " + projectInfo.getUniqueId() + " to " + projectInfo.getFullName() + " for workspace " + encodedWorkspaceName, null));
			}
			
			// Move the meta folder if the project is not linked	
			if (projectStore.equals(defaultProjectStore) && !SimpleMetaStoreUtil.moveMetaFolder(workspaceMetaFolder, projectInfo.getUniqueId(), newProjectId)) {
				throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.updateProject: could not move project: " + projectInfo.getUniqueId() + " to " + projectInfo.getFullName() + " for workspace " + encodedWorkspaceName, null));
			}
			// if the content location is local, update the content location with the new name
			if (projectInfo.getContentLocation().getScheme().equals("file")) {
				File oldContentLocation = new File(projectInfo.getContentLocation());
				if (workspaceMetaFolder.toString().equals(oldContentLocation.getParent())) {
					projectInfo.setContentLocation(new File(workspaceMetaFolder, newProjectId).toURI());
				}
			}
			// Update the workspace with the new projectName
			WorkspaceInfo workspaceInfo;
			try {
				workspaceInfo = readWorkspace(projectInfo.getWorkspaceId());
			} catch (CoreException exception) {
				throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.updateProject: could not find workspace with id:" + projectInfo.getWorkspaceId() + ".", null));
			}
			List<String> newProjectNames = new ArrayList<String>();
			newProjectNames.addAll(workspaceInfo.getProjectNames());
			String oldProjectName = SimpleMetaStoreUtil.decodeProjectNameFromProjectId(projectInfo.getUniqueId());
			newProjectNames.remove(oldProjectName);
			newProjectNames.add(projectInfo.getFullName());
			workspaceInfo.setProjectNames(newProjectNames);
			updateWorkspace(workspaceInfo);
			// update unique id with the new name
			projectInfo.setUniqueId(newProjectId);
		} else if (projectInfo.getProperty("newUserId") != null) {
			// Found the temporary properties to indicate a userId change
			String newUserId = projectInfo.getProperty("newUserId");
			String newWorkspaceId = projectInfo.getProperty("newWorkspaceId");
			projectInfo.setWorkspaceId(newWorkspaceId);

			// if the content location is local, update the content location with the new name
			if (projectInfo.getContentLocation().getScheme().equals("file")) {
				try {
					File oldContentLocation = new File(projectInfo.getContentLocation());
					if (workspaceMetaFolder.getCanonicalPath().equals(oldContentLocation.getParent())) {
						String newEncodedWorkspaceName = SimpleMetaStoreUtil.decodeWorkspaceNameFromWorkspaceId(newWorkspaceId);
						File newUserMetaFolder = SimpleMetaStoreUtil.readMetaUserFolder(getRootLocation(), newUserId);
						File newWorkspaceMetaFolder = new File(newUserMetaFolder, newEncodedWorkspaceName);
						projectInfo.setContentLocation(new File(newWorkspaceMetaFolder, projectInfo.getUniqueId()).toURI());
					}
				} catch (IOException e) {
					throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.updateProject: could not update project: " + projectInfo.getFullName() + " for workspace " + encodedWorkspaceName, null));
				}
			}

			// remove the temporary properties
			projectInfo.setProperty("newUserId", null);
			projectInfo.setProperty("newWorkspaceId", null);
		}
		try {
			jsonObject.put(SimpleMetaStore.ORION_VERSION, VERSION);
			jsonObject.put("UniqueId", projectInfo.getUniqueId());
			jsonObject.put("WorkspaceId", projectInfo.getWorkspaceId());
			jsonObject.put("FullName", projectInfo.getFullName());
			jsonObject.put("ContentLocation", projectInfo.getContentLocation());
			JSONObject properties = updateProperties(jsonObject, projectInfo);
			jsonObject.put("Properties", properties);
		} catch (JSONException e) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.updateProject: could not update project: " + projectInfo.getFullName() + " for workspace " + encodedWorkspaceName, null));
		}
		if (!SimpleMetaStoreUtil.updateMetaFile(userMetaFolder, projectInfo.getUniqueId(), jsonObject)) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.updateProject: could not update project: " + projectInfo.getFullName() + " for workspace " + encodedWorkspaceName, null));
		}
	}

	/**
	 * Update the properties that have been updated in the provided metadataInfo. The updates are determined by 
	 * looking at the the list of operations performed on the properties of this metadataInfo since the last read.
	 * See Bug 426842 for details.
	 * @param jsonObject The jsonObject to be updated with the properties changes.
	 * @param metadataInfo The updated properties JSON metadata.
	 * @return The updated JSONObject.
	 * @throws CoreException
	 */
	private JSONObject updateProperties(JSONObject jsonObject, MetadataInfo metadataInfo) throws CoreException {
		JSONObject properties = new JSONObject();
		try {
			if (jsonObject.has("Properties")) {
				properties = jsonObject.getJSONObject("Properties");
			}
			for (String key : metadataInfo.getOperations().keySet()) {
				MetadataInfo.OperationType operation = metadataInfo.getOperations().get(key);
				if (MetadataInfo.OperationType.DELETE.equals(operation)) {
					// delete the property
					if (properties.has(key)) {
						properties.remove(key);
					}
				} else {
					// create or update the property
					String value = metadataInfo.getProperties().get(key);
					if ("UserRights".equals(key)) {
						// UserRights needs to be handled specifically since it is a JSONArray and not a string.
						JSONArray userRights = new JSONArray(value);
						properties.put("UserRights", userRights);
					} else if ("SiteConfigurations".equals(key)) {
						// UserRights needs to be handled specifically since it is a JSONObject and not a string.
						JSONObject siteConfigurations = new JSONObject(value);
						properties.put("SiteConfigurations", siteConfigurations);
					} else {
						properties.put(key, value);
					}
				}
			}
			metadataInfo.flush();
		} catch (JSONException e) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.updateProperties: could not update", e));
		}
		return properties;
	}

	public void updateUser(UserInfo userInfo) throws CoreException {
		if (userInfo.getUserName() == null) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.updateUser: user name is null.", null));
		}
		if (!userInfo.getUniqueId().equals(userInfo.getUserName())) {
			// user id does not equal user name, so we are renaming the user.
			String oldUserId = userInfo.getUniqueId();
			String newUserId = userInfo.getUserName();

			// lock both the old and new userId
			ReadWriteLock oldUserLock = getLockForUser(oldUserId);
			oldUserLock.writeLock().lock();
			ReadWriteLock newUserLock = getLockForUser(newUserId);
			newUserLock.writeLock().lock();
			try {

				// update the workspace JSON with the new userId
				List<String> oldWorkspaceIds = userInfo.getWorkspaceIds();
				List<String> newWorkspaceIds = new ArrayList<String>();
				for (String oldWorkspaceId : oldWorkspaceIds) {
					WorkspaceInfo workspaceInfo = readWorkspace(oldWorkspaceId);
					workspaceInfo.setUserId(newUserId);
					updateWorkspace(workspaceInfo);
					String newWorkspaceId = workspaceInfo.getUniqueId();
					newWorkspaceIds.add(newWorkspaceId);

					// next update each of the project JSON with the new userId and location
					List<String> projectNames = workspaceInfo.getProjectNames();
					for (String projectName : projectNames) {
						ProjectInfo projectInfo = readProject(oldWorkspaceId, projectName);
						// Set temporary properties to indicate a userId change
						projectInfo.setProperty("newUserId", newUserId);
						projectInfo.setProperty("newWorkspaceId", newWorkspaceId);
						updateProject(projectInfo);
					}
				}
				// update the UserRights in the properties
				try {
					JSONObject properties = getUserProperties(userInfo);
					if (properties.has("UserRights")) {
						JSONArray userRights = properties.getJSONArray("UserRights");
						for (int i = 0; i < userRights.length(); i++) {
							JSONObject userRight = userRights.getJSONObject(i);
							String uri = userRight.getString("Uri");
							if (uri.contains(oldUserId)) {
								uri = uri.replace(oldUserId, newUserId);
								userRight.put("Uri", uri);
							}
						}
						properties.put("UserRights", userRights);
					}
					setProperties(userInfo, properties);
				} catch (JSONException e) {
					throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.updateUser: user name " + userInfo.getUserName() + " already exists, cannot rename from " + userInfo.getUniqueId(), e));
				}
				userInfo.setWorkspaceIds(newWorkspaceIds);

				// move the user folder to the new location 
				if (SimpleMetaStoreUtil.isMetaUserFolder(getRootLocation(), newUserId)) {
					throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.updateUser: user name " + userInfo.getUserName() + " already exists, cannot rename from " + userInfo.getUniqueId(), null));
				}
				if (!SimpleMetaStoreUtil.isMetaUserFolder(getRootLocation(), oldUserId)) {
					throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.updateUser: user name " + userInfo.getUniqueId() + " does not exist, cannot rename to " + userInfo.getUserName(), null));
				}
				File oldUserMetaFolder = SimpleMetaStoreUtil.readMetaUserFolder(getRootLocation(), oldUserId);
				File newUserMetaFolder = SimpleMetaStoreUtil.readMetaUserFolder(getRootLocation(), newUserId);
				SimpleMetaStoreUtil.moveUserMetaFolder(oldUserMetaFolder, newUserMetaFolder);

				userInfo.setUniqueId(newUserId);
				Logger logger = LoggerFactory.getLogger("org.eclipse.orion.server.config"); //$NON-NLS-1$
				logger.debug("Moved MetaStore for user " + oldUserId + " to user " + newUserId + "."); //$NON-NLS-1$
			} finally {
				oldUserLock.writeLock().unlock();
				newUserLock.writeLock().unlock();
			}
		}
		String userId = userInfo.getUserName();
		ReadWriteLock lock = getLockForUser(userId);
		lock.writeLock().lock();
		try {
			File userMetaFolder = SimpleMetaStoreUtil.readMetaUserFolder(getRootLocation(), userId);
			JSONObject jsonObject = SimpleMetaStoreUtil.readMetaFile(userMetaFolder, SimpleMetaStore.USER);
			if (jsonObject == null) {
				throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.updateUser: could not find user " + userId, null));
			}
			try {
				jsonObject.put("UniqueId", userInfo.getUniqueId());
				jsonObject.put("UserName", userInfo.getUserName());
				jsonObject.put("FullName", userInfo.getFullName());
				JSONArray workspaceIds = new JSONArray(userInfo.getWorkspaceIds());
				jsonObject.put("WorkspaceIds", workspaceIds);
				JSONObject properties = updateProperties(jsonObject, userInfo);
				jsonObject.put("Properties", properties);
			} catch (JSONException e) {
				throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.updateUser: could not update user: " + userId, e));
			}
			if (!SimpleMetaStoreUtil.updateMetaFile(userMetaFolder, SimpleMetaStore.USER, jsonObject)) {
				throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.updateUser: could not update user: " + userId, null));
			}
		} finally {
			lock.writeLock().unlock();
		}
	}

	public void updateWorkspace(WorkspaceInfo workspaceInfo) throws CoreException {
		String userName = SimpleMetaStoreUtil.decodeUserIdFromWorkspaceId(workspaceInfo.getUniqueId());
		String encodedWorkspaceName = SimpleMetaStoreUtil.decodeWorkspaceNameFromWorkspaceId(workspaceInfo.getUniqueId());
		boolean renameUser = false;
		String newWorkspaceId = null;
		if (!workspaceInfo.getUserId().equals(userName)) {
			// user id does not equal user name, so we are renaming the user.
			renameUser = true;
			newWorkspaceId = SimpleMetaStoreUtil.encodeWorkspaceId(workspaceInfo.getUserId(), encodedWorkspaceName);
		}
		JSONObject jsonObject = new JSONObject();
		try {
			jsonObject.put(SimpleMetaStore.ORION_VERSION, VERSION);
			if (renameUser) {
				jsonObject.put("UniqueId", newWorkspaceId);
			} else {
				jsonObject.put("UniqueId", workspaceInfo.getUniqueId());
			}
			jsonObject.put("UserId", workspaceInfo.getUserId());
			jsonObject.put("FullName", workspaceInfo.getFullName());
			JSONArray projectNames = new JSONArray(workspaceInfo.getProjectNames());
			jsonObject.put("ProjectNames", projectNames);
			JSONObject properties = updateProperties(jsonObject, workspaceInfo);
			jsonObject.put("Properties", properties);
		} catch (JSONException e) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.updateWorkspace: could not update workspace: " + encodedWorkspaceName + " for user " + userName, e));
		}
		File userMetaFolder = SimpleMetaStoreUtil.readMetaUserFolder(getRootLocation(), userName);
		if (!SimpleMetaStoreUtil.updateMetaFile(userMetaFolder, workspaceInfo.getUniqueId(), jsonObject)) {
			throw new CoreException(new Status(IStatus.ERROR, ServerConstants.PI_SERVER_CORE, 1, "SimpleMetaStore.updateWorkspace: could not update workspace: " + encodedWorkspaceName + " for user " + userName, null));
		}
		if (renameUser) {
			SimpleMetaStoreUtil.moveMetaFile(userMetaFolder, workspaceInfo.getUniqueId(), newWorkspaceId);

			workspaceInfo.setUniqueId(newWorkspaceId);
		}
	}
}