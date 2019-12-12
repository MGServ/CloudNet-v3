package de.dytanic.cloudnet.launcher.version;


import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.dytanic.cloudnet.launcher.version.util.GitCommit;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Path;

public interface VersionInfo {

    Gson GSON = new Gson();

    String GITHUB_COMMIT_API_URL = "https://api.github.com/repos/%s/commits/%s";


    String getRepositoryVersion();

    String getCurrentVersion();

    String getGitHubRepository();

    GitCommit getLatestGitCommit();

    Path getTargetDirectory();

    default GitCommit requestLatestGitCommit(String gitCommitHash) {
        if (gitCommitHash == null) {
            return GitCommit.unknown();
        }

        String commitURL = String.format(GITHUB_COMMIT_API_URL, this.getGitHubRepository(), gitCommitHash);

        try (InputStreamReader inputStreamReader = new InputStreamReader(this.readFromURL(commitURL))) {
            JsonElement jsonElement = JsonParser.parseReader(inputStreamReader);

            if (jsonElement.isJsonObject()) {
                JsonObject jsonObject = (JsonObject) jsonElement;

                if (jsonObject.has("commit")) {

                    JsonObject commitObject = jsonObject.getAsJsonObject("commit");
                    commitObject.add("sha", jsonObject.get("sha"));

                    return GSON.fromJson(commitObject, GitCommit.class);

                }
            }

        } catch (IOException exception) {
            return GitCommit.unknown();
        }

        return GitCommit.unknown();
    }

    default InputStream readFromURL(String url) throws IOException {
        URLConnection urlConnection = new URL(url).openConnection();

        urlConnection.setUseCaches(false);
        urlConnection.setDoOutput(false);

        urlConnection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.95 Safari/537.11");
        urlConnection.connect();

        return urlConnection.getInputStream();
    }

}
