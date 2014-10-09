/*******************************************************************************
 * Copyright (c) 2012, 2014 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.orion.server.tests.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.meterware.httpunit.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import org.eclipse.core.runtime.*;
import org.eclipse.orion.internal.server.core.metastore.SimpleMetaStore;
import org.eclipse.orion.internal.server.search.SearchActivator;
import org.eclipse.orion.server.tests.ServerTestsActivator;
import org.eclipse.orion.server.tests.servlets.files.FileSystemTest;
import org.eclipse.orion.server.tests.servlets.xfer.TransferTest;
import org.json.*;
import org.junit.*;
import org.junit.Test;
import org.xml.sax.SAXException;

/**
 * Tests for the search servlet.
 */
public class SearchTest extends FileSystemTest {

	private static final String SEARCH_LOCATION = toAbsoluteURI("filesearch?q=");

	@BeforeClass
	public static void setupWorkspace() {
		initializeWorkspaceLocation();
	}

	/**
	 * Asserts that a search result contains no matches.
	 */
	private void assertNoMatch(JSONObject searchResult) throws JSONException {
		JSONObject response = searchResult.getJSONObject("response");
		assertEquals(0, response.getInt("numFound"));
	}

	/**
	 * Asserts that a search result has exactly one match. Returns the matching document.
	 */
	private JSONObject assertOneMatch(JSONObject searchResult, String file) throws JSONException {
		JSONObject response = searchResult.getJSONObject("response");
		assertEquals(1, response.getInt("numFound"));
		JSONArray docs = response.getJSONArray("docs");
		JSONObject single = docs.getJSONObject(0);
		assertEquals(file, single.getString("Name"));
		return single;
	}

	/**
	 * Setup a project with some files that we can use for search tests.
	 * @throws CoreException 
	 * @throws IOException 
	 * @throws SAXException 
	 */
	private void createTestData() throws Exception {
		//create a directory to upload to
		String directoryPath = "sample/directory/path" + System.currentTimeMillis();
		createDirectory(directoryPath);

		//start the import
		URL entry = ServerTestsActivator.getContext().getBundle().getEntry("testData/searchTest/files.zip");
		File source = new File(FileLocator.toFileURL(entry).getPath());
		long length = source.length();
		IPath path = new Path("/xfer/import").append(getTestBaseResourceURILocation()).append(directoryPath);
		PostMethodWebRequest request = new PostMethodWebRequest(URIUtil.fromString(SERVER_LOCATION + path.toString()).toString());
		request.setHeaderField("X-Xfer-Content-Length", Long.toString(length));
		setAuthentication(request);
		WebResponse postResponse = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_OK, postResponse.getResponseCode());
		String location = postResponse.getHeaderField("Location");
		assertNotNull(location);

		doImport(source, length, location);
	}

	/**
	 * Copied from {@link TransferTest}.
	 */
	private void doImport(File source, long length, String location) throws FileNotFoundException, IOException, SAXException {
		//repeat putting chunks until done
		byte[] chunk = new byte[64 * 1024];
		InputStream in = new BufferedInputStream(new FileInputStream(source));
		int chunkSize = 0;
		int totalTransferred = 0;
		while ((chunkSize = in.read(chunk, 0, chunk.length)) > 0) {
			PutMethodWebRequest put = new PutMethodWebRequest(toAbsoluteURI(location), new ByteArrayInputStream(chunk, 0, chunkSize), "application/zip");
			put.setHeaderField("Content-Range", "bytes " + totalTransferred + "-" + (totalTransferred + chunkSize - 1) + "/" + length);
			put.setHeaderField("Content-Length", "" + length);
			put.setHeaderField("Content-Type", "application/zip");
			setAuthentication(put);
			totalTransferred += chunkSize;
			WebResponse putResponse = webConversation.getResponse(put);
			if (totalTransferred == length) {
				assertEquals(201, putResponse.getResponseCode());
			} else {
				assertEquals(308, putResponse.getResponseCode());
				String range = putResponse.getHeaderField("Range");
				assertEquals("bytes 0-" + (totalTransferred - 1), range);
			}
		}
		in.close();
	}

	private JSONObject doSearch(String query) throws Exception {
		WebRequest request = new GetMethodWebRequest(SEARCH_LOCATION + query);
		setAuthentication(request);
		WebResponse response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_OK, response.getResponseCode());
		return new JSONObject(response.getText());
	}

	@Before
	public void setUp() throws Exception {
		HttpUnitOptions.setDefaultCharacterSet("UTF-8");
		webConversation = new WebConversation();
		webConversation.setExceptionsThrownOnErrorStatus(false);
		setUpAuthorization();
		createWorkspace(SimpleMetaStore.DEFAULT_WORKSPACE_NAME);
		createTestProject(testName.getMethodName());
		createTestData();
		//wait for indexer to finish
		SearchActivator.getInstance().testWaitForIndex();
	}

	/**
	 * Tests finding search results on a part of a word.
	 */
	@Test
	public void testPartialWord() throws Exception {
		JSONObject searchResult = doSearch("*ErrorMess");
		JSONObject match = assertOneMatch(searchResult, "script.js");

		//query with location
		String location = match.getString("Location");
		searchResult = doSearch("*ErrorMess+Location:" + location);
		assertOneMatch(searchResult, "script.js");
	}

	/**
	 * Tests that we don't concatenate over punctuation boundaries.
	 * @throws Exception 
	 */
	@Test
	public void testWordConcatenation() throws Exception {
		JSONObject searchResult = doSearch("jsondata.Children");
		assertOneMatch(searchResult, "script.js");
		searchResult = doSearch("jsondataChildren");
		assertNoMatch(searchResult);

		searchResult = doSearch("functionparentLocation");
		assertNoMatch(searchResult);
		searchResult = doSearch("function(parentLocation");
		assertOneMatch(searchResult, "script.js");

	}

	/**
	 * Tests a malicious search that attempts to inject HTML.
	 * @throws Exception
	 */
	@Test
	public void testHTMLTag() throws Exception {
		JSONObject searchResult = doSearch("<img src=\"http://i.imgur.com/3dLMJ.jpg\" alt=\"Mark Macdonald\" title=\"Mark Macdonald\">");
		assertNoMatch(searchResult);
	}

	/**
	 * Tests searching for special characters.
	 */
	@Test
	public void testSearchNonAlphaNumeric() throws Exception {
		JSONObject searchResult = doSearch("amber&sand");
		assertOneMatch(searchResult, "a.txt");
		searchResult = doSearch("per%25cent");
		assertOneMatch(searchResult, "a.txt");
		searchResult = doSearch("car%5Eat");
		assertOneMatch(searchResult, "a.txt");
		searchResult = doSearch("do%24%24ar");
		assertOneMatch(searchResult, "a.txt");
		searchResult = doSearch("has%23");
		assertOneMatch(searchResult, "a.txt");
		searchResult = doSearch("at%40sign");
		assertOneMatch(searchResult, "a.txt");
	}

	/**
	 * Tests for special characters that are treated as delimiters
	 * and replaced with blanks during tokenization
	 */
	@Test
	public void testFilteredDelimiters() throws Exception {
		// equals sign
		JSONObject searchResult = doSearch("equals%3Dcharacter");
		assertNoMatch(searchResult);
		searchResult = doSearch("equals character");
		assertNoMatch(searchResult);
		searchResult = doSearch("\"equals character\"");
		assertOneMatch(searchResult, "page.html");

		//slash
		searchResult = doSearch("orion/es5shim");
		assertNoMatch(searchResult);
		searchResult = doSearch("orion es5shim");
		assertNoMatch(searchResult);
		searchResult = doSearch("\"orion es5shim\"");
		assertOneMatch(searchResult, "script.js");
		searchResult = doSearch("orion");
		assertOneMatch(searchResult, "script.js");
		searchResult = doSearch("es5shim");
		assertOneMatch(searchResult, "script.js");

		//  colon
		searchResult = doSearch("co:lon");
		assertNoMatch(searchResult);
		searchResult = doSearch("\"co lon\"");
		assertOneMatch(searchResult, "a.txt");

		//  comma
		searchResult = doSearch("comma,comma");
		assertNoMatch(searchResult);
		searchResult = doSearch("\"comma comma\"");
		assertOneMatch(searchResult, "a.txt");
		searchResult = doSearch("comma");
		assertOneMatch(searchResult, "a.txt");

		// semicolon
		searchResult = doSearch("semi;colon");
		assertNoMatch(searchResult);
		searchResult = doSearch("\"semi colon\"");
		assertOneMatch(searchResult, "a.txt");
		searchResult = doSearch("semi");
		assertOneMatch(searchResult, "a.txt");
		searchResult = doSearch("colon");
		assertOneMatch(searchResult, "a.txt");
	}

	/**
	 * Test for bracket-ish delimiters that are replaced with
	 * blanks during tokenization.
	 */
	@Test
	public void testBracketishDelimiters() throws Exception {
		JSONObject searchResult = doSearch("(round)");
		assertNoMatch(searchResult);
		searchResult = doSearch("round");
		assertOneMatch(searchResult, "a.txt");
		searchResult = doSearch("[squared]");
		assertNoMatch(searchResult);
		searchResult = doSearch("squared");
		assertOneMatch(searchResult, "a.txt");
		searchResult = doSearch("{curly}");
		assertNoMatch(searchResult);
		searchResult = doSearch("curly");
		assertOneMatch(searchResult, "a.txt");
		searchResult = doSearch("<tagged>");
		assertNoMatch(searchResult);
		searchResult = doSearch("tagged");
		assertOneMatch(searchResult, "a.txt");
		searchResult = doSearch("crazyfunc(param4711,param0815)");
		assertNoMatch(searchResult);
		searchResult = doSearch("\"crazyfunc param4711 param0815\"");
		assertOneMatch(searchResult, "a.txt");
		searchResult = doSearch("crazyfunc");
		assertOneMatch(searchResult, "a.txt");
		searchResult = doSearch("param4711");
		assertOneMatch(searchResult, "a.txt");
		searchResult = doSearch("param0815");
		assertOneMatch(searchResult, "a.txt");
	}

	/**
	 * Tests searching for a sequence of words in a particular order
	 * @throws Exception
	 */
	@Test
	public void testPhraseSearch() throws Exception {
		//these same words appear in a.txt in a different order
		JSONObject searchResult = doSearch("%22Eclipse Public License%22");
		assertOneMatch(searchResult, "script.js");
	}

	/**
	 * Tests searching in a project whose name starts with square bracket.
	 * This is a regression test for bug 417124.
	 */
	@Test
	public void testSearchInProjectWithURLName() throws Exception {
		final String projectName = "[breakme]";
		createTestProject(projectName);
		createFile("smaug.txt", "Chiefest and Greatest of Calamities");
		SearchActivator.getInstance().testWaitForIndex();

		JSONObject searchResult = doSearch("Calamities");
		assertOneMatch(searchResult, "smaug.txt");

	}

	/**
	 * Tests searching for a sequence of words in a particular order
	 * @throws Exception
	 */
	@Test
	public void testPhraseDifferentCase() throws Exception {
		//these same words appear in a.txt in a different order
		JSONObject searchResult = doSearch("%22ECLIPSE public LiCeNse%22");
		assertOneMatch(searchResult, "script.js");
	}

	/**
	 * Tests finding search results on a whole single word.
	 */
	@Test
	public void testSingleWord() throws Exception {
		//simple word in html tag
		JSONObject searchResult = doSearch("monkey");
		assertOneMatch(searchResult, "page.html");

		//word in quotes
		searchResult = doSearch("mammoth");
		assertOneMatch(searchResult, "a.txt");

		//compound word
		searchResult = doSearch("Will-o'-the-wisp");
		assertOneMatch(searchResult, "a.txt");

		//should not match
		searchResult = doSearch("Will-o'-the-whisk");
		assertNoMatch(searchResult);
	}

	/**
	 * Tests finding search results in a directory with double byte character set folder name.
	 */
	@Test
	public void testPathWithDBCS() throws Exception {

		//simple word
		JSONObject searchResult = doSearch("badger");
		assertOneMatch(searchResult, "dbcs-folder.txt");

		//wildcard
		searchResult = doSearch("*badg?r");
		JSONObject match = assertOneMatch(searchResult, "dbcs-folder.txt");

		//query with location
		String location = match.getString("Location");
		searchResult = doSearch("badger+Location:" + location);
		match = assertOneMatch(searchResult, "dbcs-folder.txt");
	}

	/**
	 * Tests finding search results with both the file and directory with double byte characters in the name.
	 */
	@Test
	public void testFileWithDBCS() throws Exception {

		//simple word
		JSONObject searchResult = doSearch("gazelle");
		assertOneMatch(searchResult, "\u65e5\u672c\u8a9e.txt");

		//wildcard
		searchResult = doSearch("*ga?elle");
		JSONObject match = assertOneMatch(searchResult, "\u65e5\u672c\u8a9e.txt");

		//query with location
		String location = match.getString("Location");
		searchResult = doSearch("gazelle+Location:" + location);
		match = assertOneMatch(searchResult, "\u65e5\u672c\u8a9e.txt");
	}

	/**
	 * Tests finding search results of double byte characters.
	 * Commented out due to failure. See bug 409766 for details.
	 */
	public void testSearchDBCS() throws Exception {

		//simple word
		JSONObject searchResult = doSearch("\u4e56\u4e56");
		assertOneMatch(searchResult, "DBCS.txt");

		//wildcard
		searchResult = doSearch("\u65e5?\u8a9e");
		JSONObject match = assertOneMatch(searchResult, "DBCS.txt");

		//query with location
		String location = match.getString("Location");
		searchResult = doSearch("\u4e56+Location:" + location);
		match = assertOneMatch(searchResult, "DBCS.txt");
	}

	/**
	 * Tests finding search results in a file and directory with whitespace in the name.
	 */
	@Test
	public void testPathWithSpaces() throws Exception {

		//simple word
		JSONObject searchResult = doSearch("oryx");
		assertOneMatch(searchResult, "file with spaces.txt");

		//wildcard
		searchResult = doSearch("*lem?r");
		JSONObject match = assertOneMatch(searchResult, "file with spaces.txt");

		//query with location
		String location = match.getString("Location");
		searchResult = doSearch("oryx+Location:" + location);
		match = assertOneMatch(searchResult, "file with spaces.txt");

		//same query with wildcard on location
		location = match.getString("Location");
		location = location.substring(0, location.length() - "file%20with%20spaces.txt".length());
		searchResult = doSearch("oryx+Location:" + location + '*');
		match = assertOneMatch(searchResult, "file with spaces.txt");

	}

}
