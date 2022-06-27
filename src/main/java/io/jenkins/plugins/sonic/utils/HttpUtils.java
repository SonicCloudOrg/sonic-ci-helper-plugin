/*
 *  Copyright (C) [SonicCloudOrg] Sonic Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package io.jenkins.plugins.sonic.utils;

import com.ejlchina.data.TypeRef;
import com.ejlchina.okhttps.*;
import com.ejlchina.okhttps.Process;
import com.ejlchina.okhttps.gson.GsonMsgConvertor;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.util.Secret;
import io.jenkins.plugins.sonic.Messages;
import io.jenkins.plugins.sonic.SonicGlobalConfiguration;
import io.jenkins.plugins.sonic.bean.*;
import io.jenkins.plugins.sonic.bean.HttpResult;
import org.apache.tools.ant.DirectoryScanner;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class HttpUtils {
    private static final String UPLOAD_URL = "/server/api/folder/upload";
    private static final String PROJECT_URL = "/api/controller/projects/list";
    private static final String PACKAGE_URL = "/server/api/controller/packages";
    private static final String SonicToken = "SonicToken";
    private static final String BRANCH = "${GIT_BRANCH}";
    private static final String BUILD_URL = "${BUILD_URL}";
    private static final HTTP http = HTTP.builder()
            .bodyType("json")
            .addMsgConvertor(new GsonMsgConvertor())
            .build();


    public static boolean upload(AbstractBuild<?, ?> build, BuildListener listener, ParamBean paramBean) throws IOException, InterruptedException {

        paramBean.setHost(build.getEnvironment(listener).expand(paramBean.getHost()));
        paramBean.setApiKey(Secret.fromString(build.getEnvironment(listener).expand(Secret.toString(paramBean.getApiKey()))));
        paramBean.setScanDir(build.getEnvironment(listener).expand(paramBean.getScanDir()));

        if (!StringUtils.hasText(paramBean.getProjectId())) {
            Logging.logging(listener, Messages.UploadBuilder_Http_error_missProjectId());
            return false;
        }

        String path = findFile(paramBean.getScanDir(), listener);
        if (!StringUtils.hasText(path)) {
            Logging.logging(listener, Messages.UploadBuilder_Http_error_missFile());
            return false;
        }
        File uploadFile = new File(path);
        if (!uploadFile.exists() || !uploadFile.isFile()) {
            Logging.logging(listener, Messages.UploadBuilder_Http_error_missFile());
            return false;
        }
        Logging.printHeader(listener);
        String url = upload(build, uploadFile, listener, paramBean);

        if (url == null) {
            return false;
        }
        String branch = getBranch(build, listener);
        String buildUrl = getBuildUrl(build, listener);
        savePackageInfo(paramBean, listener, uploadFile.getName(), url, platform(uploadFile.getName()), branch, buildUrl);
        return true;
    }

    private static String getBranch(AbstractBuild<?, ?> build, BuildListener listener) throws IOException, InterruptedException {
        String branch = build.getEnvironment(listener).expand(BRANCH);

        if (BRANCH.equals(branch)) {
            return "unknown";
        }
        return branch;
    }

    private static String getBuildUrl(AbstractBuild<?, ?> build, BuildListener listener) throws IOException, InterruptedException {
        String buildUrl = build.getEnvironment(listener).expand(BUILD_URL);

        if (BUILD_URL.equals(buildUrl)) {
            return "unknown";
        }
        return buildUrl;
    }

    private static String upload(AbstractBuild<?, ?> build, File uploadFile, BuildListener listener, ParamBean paramBean) {
        HttpCall call = buildHttp(paramBean, UPLOAD_URL)
                .addFilePara("file", uploadFile)
                .addBodyPara("type", "packageFiles")
                .stepRate(0.05)    // 设置每发送 1% 执行一次进度回调（不设置以 StepBytes 为准）
                .setOnProcess(process -> Logging.logging(listener, "upload progress: " + (int) (process.getRate() * 100) + " %"))
                .setOnException(e -> {
                    Logging.logging(listener, "upload exception: ");
                    Logging.logging(listener, e.fillInStackTrace().toString());
                })
                .post();
        if (call.getResult().isSuccessful()) {
            HttpResult<String> httpResult = call.getResult().getBody().toBean(new TypeRef<HttpResult<String>>() {
                @Override
                public Type getType() {
                    return super.getType();
                }
            });
            Logging.logging(listener, "${appURL}: " + httpResult.getData());
            build.addAction(new PublishEnvVarAction("appURL", httpResult.getData()));

            return httpResult.getData();
        }
        Logging.logging(listener, "===================");
        Logging.logging(listener, "Upload file failed.");
        Logging.logging(listener, call.getResult().toString());
        Logging.logging(listener, "===================");
        return null;
    }

    private static void savePackageInfo(ParamBean paramBean, BuildListener listener, String name,
                                        String url, String platform, String branch, String buildUrl) {

        PackageBean packageBean = new PackageBean();
        packageBean.setPkgName(name);
        packageBean.setUrl(url);
        packageBean.setPlatform(platform);
        packageBean.setProjectId(Integer.parseInt(paramBean.getProjectId()));
        packageBean.setBranch(branch);
        packageBean.setBuildUrl(buildUrl);

        com.ejlchina.okhttps.HttpResult result = http.sync(paramBean.getHost() + PACKAGE_URL)
                .addHeader(SonicToken, Secret.toString(paramBean.getApiKey()))
                .setBodyPara(packageBean)
                .put();


        if (result.isSuccessful()) {
            HttpResult<String> httpResult = result.getBody().toBean(new TypeRef<HttpResult<String>>() {
                @Override
                public Type getType() {
                    return super.getType();
                }
            });
            Logging.logging(listener, "===================");
            Logging.logging(listener, "Send package info successful!");
            Logging.logging(listener, httpResult.toString());
        } else {
            Logging.logging(listener, "===================");
            Logging.logging(listener, "Send package info failed.");
            Logging.logging(listener, result.toString());

        }
        Logging.logging(listener, "===================");

    }

    private static AHttpTask buildHttp(ParamBean paramBean, String uri) {
        return http.async(paramBean.getHost() + uri)
                .addHeader(SonicToken, Secret.toString(paramBean.getApiKey()));
    }

    public static HttpResult<List<Project>> listProject() {
        String host = SonicGlobalConfiguration.get().getHost();

        if (host == null) {
            throw new AssertionError("Sonic Url is null! Please set it in GlobalConfiguration.");
        }
        return http.sync(host + PROJECT_URL)
                .get()
                .getBody()
                .toBean(new TypeRef<HttpResult<List<Project>>>() {
                    @Override
                    public Type getType() {
                        return super.getType();
                    }
                });

    }


    public static String findFile(String scandir, BuildListener listener) {
        File dir = new File(scandir);
        if (!dir.exists() || !dir.isDirectory()) {
            Logging.logging(listener, "Scan dir:" + dir.getAbsolutePath());
            Logging.logging(listener, "Scan dir isn't exist or it's not a directory!");
            return null;
        }

        DirectoryScanner scanner = new DirectoryScanner();
        scanner.setBasedir(scandir);
        scanner.setIncludes(new String[]{"ipa", "apk"});
        scanner.setCaseSensitive(true);
        scanner.scan();
        String[] uploadFiles = scanner.getIncludedFiles();

        if (uploadFiles == null || uploadFiles.length == 0) {
            return null;
        }
        if (uploadFiles.length == 1) {
            return new File(dir, uploadFiles[0]).getAbsolutePath();
        }

        List<String> strings = Arrays.asList(uploadFiles);
        Collections.sort(strings, (o1, o2) -> {
            File file1 = new File(dir, o1);
            File file2 = new File(dir, o2);
            return Long.compare(file2.lastModified(), file1.lastModified());
        });
        String uploadFiltPath = new File(dir, strings.get(0)).getAbsolutePath();
        Logging.logging(listener, "Found " + uploadFiles.length + " files, the default choice of the latest modified file!");
        Logging.logging(listener, "The latest modified file is " + uploadFiltPath);
        return uploadFiltPath;
    }


    private static String platform(String fileName) {
        String ext = fileName.substring(fileName.lastIndexOf("."));

        if (!StringUtils.hasText(ext)) {
            return "unknown";
        }
        if (ext.contains("ipa")) {
            return "iOS";
        }
        return "Android";
    }
}
