/*******************************************************************************
 * Copyright (c) 2012 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.orion.server.tests.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.Map;

import org.eclipse.orion.internal.server.core.IOUtilities;
import org.junit.Test;

public class IOUtilitiesTest {
	@Test
	public void testParseMultiPartWithURL() throws Exception {
		StringBuilder sb = new StringBuilder();
		sb.append("--24464570528145").append("\n");
		sb.append("Content-Disposition: form-data; name=\"radio\"").append("\n");
		sb.append("").append("\n");
		sb.append("urlRadio").append("\n");
		sb.append("--24464570528145").append("\n");
		sb.append("Content-Disposition: form-data; name=\"url\"").append("\n");
		sb.append("").append("\n");
		sb.append("http://localhost:8080/gitapi/diff/Default/file/r6/?parts=diff&Path=file.txt").append("\n");
		sb.append("--24464570528145").append("\n");
		sb.append("Content-Disposition: form-data; name=\"uploadedfile\"; filename=\"\"").append("\n");
		sb.append("Content-Type: application/octet-stream").append("\n");
		sb.append("").append("\r\n");
		sb.append("").append("\n");
		sb.append("--24464570528145--").append("\n");

		ByteArrayInputStream is = new ByteArrayInputStream(sb.toString().getBytes());
		Map<String, String> parts = IOUtilities.parseMultiPart(is, "24464570528145");
		assertNotNull(parts);
		assertEquals("urlRadio", parts.get("radio"));
		assertEquals("http://localhost:8080/gitapi/diff/Default/file/r6/?parts=diff&Path=file.txt", parts.get("url"));
		assertEquals("", parts.get("uploadedfile"));
	}

	@Test
	public void testParseMultiPartWithPatch() throws Exception {
		StringBuilder patch = new StringBuilder();
		patch.append("diff --git a/test.txt b/test.txt").append("\n");
		patch.append("index 30d74d2..8013df8 100644").append("\n");
		patch.append("--- a/test.txt").append("\n");
		patch.append("+++ b/test.txt").append("\n");
		patch.append("@@ -1 +1 @@").append("\n");
		patch.append("-test").append("\n");
		patch.append("\\ No newline at end of file").append("\n");
		patch.append("+patched").append("\n");
		patch.append("\\ No newline at end of file").append("\n");

		StringBuilder sb = new StringBuilder();
		sb.append("--24464570528145").append("\n");
		sb.append("Content-Disposition: form-data; name=\"radio\"").append("\n");
		sb.append("").append("\n");
		sb.append("fileRadio").append("\n");
		sb.append("--24464570528145").append("\n");
		sb.append("Content-Disposition: form-data; name=\"uploadedfile\"; filename=\"\"").append("\n");
		sb.append("Content-Type: application/octet-stream").append("\n");
		sb.append("asd").append("\n");
		sb.append(patch.toString()).append("\n");
		sb.append("--24464570528145--");

		ByteArrayInputStream is = new ByteArrayInputStream(sb.toString().getBytes());
		Map<String, String> parts = IOUtilities.parseMultiPart(is, "24464570528145");
		assertNotNull(parts);
		assertEquals("fileRadio", parts.get("radio"));
		assertNull(parts.get("url"));
		assertEquals(patch.toString(), parts.get("uploadedfile"));
	}

	@Test
	public void testGetLine() throws IOException {
		StringBuilder testStringBuilder = new StringBuilder();
		testStringBuilder.append("test content test content test content test content test content");
		testStringBuilder.append("\n");
		testStringBuilder.append("");
		testStringBuilder.append("\r\n");
		testStringBuilder.append("\r");
		testStringBuilder.append("test content test content test content");
		String testString = testStringBuilder.toString();

		StringBuilder readStringBuilder = new StringBuilder();
		BufferedReader reader = new BufferedReader(new StringReader(testString));
		String[] line;
		while ((line = IOUtilities.getLine(reader))[1] != "") {
			readStringBuilder.append(line[0]);
			readStringBuilder.append(line[1]);
		}
		readStringBuilder.append(line[0]);
		assertEquals(readStringBuilder.toString(), testString);
	}
}
