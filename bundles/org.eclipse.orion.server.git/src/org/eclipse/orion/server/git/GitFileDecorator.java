/*******************************************************************************
 * Copyright (c) 2011, 2013 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.orion.server.git;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import javax.servlet.http.HttpServletRequest;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.runtime.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.orion.internal.server.servlets.ProtocolConstants;
import org.eclipse.orion.internal.server.servlets.ServletResourceHandler.Method;
import org.eclipse.orion.internal.server.servlets.workspace.WorkspaceResourceHandler;
import org.eclipse.orion.server.core.*;
import org.eclipse.orion.server.core.metastore.IMetaStore;
import org.eclipse.orion.server.core.metastore.ProjectInfo;
import org.eclipse.orion.server.git.objects.*;
import org.eclipse.orion.server.git.objects.Status;
import org.eclipse.orion.server.git.servlets.GitServlet;
import org.eclipse.orion.server.git.servlets.GitUtils;
import org.json.*;

/**
 * Adds links to workspace and file resources referring to related git resources.
 */
public class GitFileDecorator implements IWebResourceDecorator {

	@Override
	public void addAtributesFor(HttpServletRequest request, URI resource, JSONObject representation) {
		String requestPath = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
		IPath targetPath = new Path(requestPath);
		if (targetPath.segmentCount() <= 1)
			return;
		String servlet = request.getServletPath();
		if (!"/file".equals(servlet) && !"/workspace".equals(servlet)) //$NON-NLS-1$ //$NON-NLS-2$
			return;

		boolean isWorkspace = ("/workspace".equals(servlet)); //$NON-NLS-1$

		try {
			if (isWorkspace && Method.POST.equals(Method.fromString(request.getMethod()))) {
				String contentLocation = representation.getString(ProtocolConstants.KEY_CONTENT_LOCATION);

				// initialize a new git repository on project creation if specified by configuration
				initGitRepository(request, targetPath, representation);
				addGitLinks(request, new URI(contentLocation), representation);
				return;
			}

			if (isWorkspace && Method.GET.equals(Method.fromString(request.getMethod()))) {
				JSONArray children = representation.optJSONArray(ProtocolConstants.KEY_CHILDREN);
				if (children != null) {
					for (int i = 0; i < children.length(); i++) {
						JSONObject child = children.getJSONObject(i);
						String location = child.getString(ProtocolConstants.KEY_LOCATION);
						if (location == null || location.length() == 0) {
							String childName = child.optString(ProtocolConstants.KEY_NAME);
							LogHelper.log(new RuntimeException("Unexpected null location for child" + childName + " of resource " + resource));
						}
						addGitLinks(request, new URI(location), child);
					}
				}
				return;
			}

			if (!isWorkspace && Method.GET.equals(Method.fromString(request.getMethod()))) {
				//compute all git properties in advance because it will be same for all children
				Repository db = repositoryForPath(request, new Path(resource.getPath()));
				URI cloneLocation = BaseToCloneConverter.getCloneLocation(resource, BaseToCloneConverter.FILE);
				String branch = db == null ? null : db.getBranch();
				Remote defaultRemote = db == null ? null : new Remote(cloneLocation, db, Constants.DEFAULT_REMOTE_NAME);
				RemoteBranch defaultRemoteBranch = db == null ? null : new RemoteBranch(cloneLocation, db, defaultRemote, branch);
				addGitLinks(request, resource, representation, cloneLocation, db, defaultRemoteBranch, branch);

				JSONArray children = representation.optJSONArray(ProtocolConstants.KEY_CHILDREN);
				if (children != null) {
					for (int i = 0; i < children.length(); i++) {
						JSONObject child = children.getJSONObject(i);
						String location = child.getString(ProtocolConstants.KEY_LOCATION);
						if (db != null) {
							// if parent was a git repository we can reuse information computed above
							addGitLinks(request, new URI(location), child, cloneLocation, db, defaultRemoteBranch, branch);
						} else {
							//maybe the child is the root of a git repository
							addGitLinks(request, new URI(location), child);
						}
					}
				}
				if (db != null) {
					// close the git repository
					db.close();
				}
			}
		} catch (Exception e) {
			// log and continue
			LogHelper.log(e);
		}
	}

	private void addGitLinks(HttpServletRequest request, URI location, JSONObject representation) throws URISyntaxException, JSONException, CoreException, IOException {
		Repository db = repositoryForPath(request, new Path(location.getPath()));
		if (db == null)
			return;
		URI cloneLocation = BaseToCloneConverter.getCloneLocation(location, BaseToCloneConverter.FILE);
		String branch = db.getBranch();
		Remote defaultRemote = new Remote(cloneLocation, db, Constants.DEFAULT_REMOTE_NAME);
		RemoteBranch defaultRemoteBranch = new RemoteBranch(cloneLocation, db, defaultRemote, branch);
		addGitLinks(request, location, representation, cloneLocation, db, defaultRemoteBranch, branch);
		// close the git repository
		db.close();
	}

	private void addGitLinks(HttpServletRequest request, URI location, JSONObject representation, URI cloneLocation, Repository db, RemoteBranch defaultRemoteBranch, String branchName) throws URISyntaxException, JSONException {
		if (db == null)
			return;
		IPath targetPath = new Path(location.getPath());

		JSONObject gitSection = new JSONObject();

		// add Git Diff URI
		IPath path = new Path(GitServlet.GIT_URI + '/' + Diff.RESOURCE + '/' + GitConstants.KEY_DIFF_DEFAULT).append(targetPath);
		URI link = new URI(location.getScheme(), location.getAuthority(), path.toString(), null, null);
		gitSection.put(GitConstants.KEY_DIFF, link);

		// add Git Status URI
		path = new Path(GitServlet.GIT_URI + '/' + Status.RESOURCE).append(targetPath);
		link = new URI(location.getScheme(), location.getAuthority(), path.toString(), null, null);
		gitSection.put(GitConstants.KEY_STATUS, link);

		// add Git Index URI
		path = new Path(GitServlet.GIT_URI + '/' + Index.RESOURCE).append(targetPath);
		link = new URI(location.getScheme(), location.getAuthority(), path.toString(), null, null);
		gitSection.put(GitConstants.KEY_INDEX, link);

		// add Git Ignore URI
		path = new Path(GitServlet.GIT_URI + '/' + Ignore.RESOURCE).append(targetPath);
		link = new URI(location.getScheme(), location.getAuthority(), path.toString(), null, null);
		gitSection.put(GitConstants.KEY_IGNORE, link);

		// add Git HEAD URI
		path = new Path(GitServlet.GIT_URI + '/' + Commit.RESOURCE).append(Constants.HEAD).append(targetPath);
		link = new URI(location.getScheme(), location.getAuthority(), path.toString(), null, null);
		gitSection.put(GitConstants.KEY_HEAD, link);

		// add Git Commit URI
		path = new Path(GitServlet.GIT_URI + '/' + Commit.RESOURCE).append(GitUtils.encode(branchName)).append(targetPath);
		link = new URI(location.getScheme(), location.getAuthority(), path.toString(), null, null);
		gitSection.put(GitConstants.KEY_COMMIT, link);

		// add Git Remote URI
		path = new Path(GitServlet.GIT_URI + '/' + Remote.RESOURCE).append(targetPath);
		link = new URI(location.getScheme(), location.getAuthority(), path.toString(), null, null);
		gitSection.put(GitConstants.KEY_REMOTE, link);

		// add Git Clone Config URI
		path = new Path(GitServlet.GIT_URI + '/' + ConfigOption.RESOURCE + '/' + Clone.RESOURCE).append(targetPath);
		link = new URI(location.getScheme(), location.getAuthority(), path.toString(), null, null);
		gitSection.put(GitConstants.KEY_CONFIG, link);

		// add Git Default Remote Branch URI 
		gitSection.put(GitConstants.KEY_DEFAULT_REMOTE_BRANCH, defaultRemoteBranch.getLocation());

		// add Git Tag URI
		path = new Path(GitServlet.GIT_URI + '/' + Tag.RESOURCE).append(targetPath);
		link = new URI(location.getScheme(), location.getAuthority(), path.toString(), null, null);
		gitSection.put(GitConstants.KEY_TAG, link);

		// add Git Blame URI
		path = new Path(GitServlet.GIT_URI + '/' + Blame.RESOURCE).append(Constants.HEAD).append(targetPath);
		link = new URI(location.getScheme(), location.getAuthority(), path.toString(), null, null);
		gitSection.put(GitConstants.KEY_BLAME, link);

		// add Git Clone URI
		gitSection.put(GitConstants.KEY_CLONE, cloneLocation);

		path = new Path(GitServlet.GIT_URI + "/" + Stash.RESOURCE).append(targetPath);
		link = new URI(location.getScheme(), location.getAuthority(), path.toString(), null, null);
		gitSection.put(GitConstants.KEY_STASH, link);

		representation.put(GitConstants.KEY_GIT, gitSection);
	}

	private Repository repositoryForPath(HttpServletRequest request, IPath targetPath) throws CoreException, IOException {
		IPath requestPath = targetPath;

		if (request.getContextPath().length() != 0) {
			IPath contextPath = new Path(request.getContextPath());
			if (contextPath.isPrefixOf(targetPath)) {
				requestPath = targetPath.removeFirstSegments(contextPath.segmentCount());
			}
		}

		File gitDir = GitUtils.getGitDir(requestPath);
		if (gitDir == null)
			return null;

		Repository db = FileRepositoryBuilder.create(gitDir);
		return db;
	}

	/**
	 * If this  server is configured to use git by default, then each project creation that is not
	 * already in a repository needs to have a git -init performed to initialize the repository.
	 */
	private void initGitRepository(HttpServletRequest request, IPath targetPath, JSONObject representation) {
		//project creation URL is of the form POST /workspace/<id>
		if (!(targetPath.segmentCount() == 2 && "workspace".equals(targetPath.segment(0)) && Method.POST.equals(Method.fromString(request.getMethod())))) //$NON-NLS-1$
			return;
		String scm = PreferenceHelper.getString(ServerConstants.CONFIG_FILE_DEFAULT_SCM, "").toLowerCase(); //$NON-NLS-1$
		if (!"git".equals(scm)) //$NON-NLS-1$
			return;
		try {
			ProjectInfo project = getProjectForLocation(representation.getString(ProtocolConstants.KEY_LOCATION));
			if (project == null)
				return;
			IFileStore store = project.getProjectStore();
			//create repository in each project if it doesn't already exist
			File localFile = store.toLocalFile(EFS.NONE, null);
			File gitDir = GitUtils.getGitDir(localFile);
			if (gitDir == null) {
				gitDir = new File(localFile, Constants.DOT_GIT);
				Repository repo = FileRepositoryBuilder.create(gitDir);
				repo.create();
				//we need to perform an initial commit to workaround JGit bug 339610.
				Git git = new Git(repo);
				git.add().addFilepattern(".").call(); //$NON-NLS-1$
				git.commit().setMessage("Initial commit").call();
				// close the git repository
				repo.close();
			}
		} catch (Exception e) {
			//just log it - this is not the purpose of the file decorator
			LogHelper.log(e);
		}
	}

	/**
	 * Returns the project for the given metadata location, or <code>null</code>.
	 * @throws CoreException 
	 */
	private ProjectInfo getProjectForLocation(String location) throws CoreException {
		//location is URI of the form protocol:/workspace/workspaceId/project/projectName
		IMetaStore store = OrionConfiguration.getMetaStore();
		return WorkspaceResourceHandler.projectForMetadataLocation(store, location);
	}
}
