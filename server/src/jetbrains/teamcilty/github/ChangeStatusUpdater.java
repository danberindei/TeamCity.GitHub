/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.teamcilty.github;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.BuildProblemData;
import jetbrains.buildServer.BuildProblemTypes;
import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.serverSide.executors.ExecutorServices;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.util.ExceptionUtil;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.vcs.SelectPrevBuildPolicy;
import jetbrains.teamcilty.github.api.*;
import jetbrains.teamcilty.github.ui.UpdateChangeStatusFeature;
import jetbrains.teamcilty.github.ui.UpdateChangesConstants;
import jetbrains.teamcilty.github.util.LoggerHelper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Created by Eugene Petrenko (eugene.petrenko@gmail.com)
 * Date: 06.09.12 3:29
 */
public class ChangeStatusUpdater {
  private static final Logger LOG = LoggerHelper.getInstance(ChangeStatusUpdater.class);
  private static final UpdateChangesConstants C = new UpdateChangesConstants();

  private final ExecutorService myExecutor;
  @NotNull
  private final GitHubApiFactory myFactory;
  private final WebLinks myWeb;

  public ChangeStatusUpdater(@NotNull final ExecutorServices services,
                             @NotNull final GitHubApiFactory factory,
                             @NotNull final WebLinks web) {
    myFactory = factory;
    myWeb = web;
    myExecutor = services.getLowPriorityExecutorService();
  }

  @NotNull
  private GitHubApi getGitHubApi(@NotNull final SBuildFeatureDescriptor feature) {
    final String serverUrl = feature.getParameters().get(C.getServerKey());
    if (serverUrl == null || StringUtil.isEmptyOrSpaces(serverUrl)) {
      throw new IllegalArgumentException("Failed to read GitHub URL from the feature settings");
    }

    final GitHubApiAuthenticationType authenticationType = GitHubApiAuthenticationType.parse(feature.getParameters().get(C.getAuthenticationTypeKey()));
    switch (authenticationType) {
      case PASSWORD_AUTH:
        final String username = feature.getParameters().get(C.getUserNameKey());
        final String password = feature.getParameters().get(C.getPasswordKey());
        return myFactory.openGitHubForUser(serverUrl, username, password);

      case TOKEN_AUTH:
        final String token = feature.getParameters().get(C.getAccessTokenKey());
        return myFactory.openGitHubForToken(serverUrl, token);

      default:
        throw new IllegalArgumentException("Failed to parse authentication type:" + authenticationType);
    }
  }

  @NotNull
  public Handler getUpdateHandler(@NotNull final SBuildFeatureDescriptor feature) {
    if (!feature.getType().equals(UpdateChangeStatusFeature.FEATURE_TYPE)) {
      throw new IllegalArgumentException("Unexpected feature type " + feature.getType());
    }

    final GitHubApi api = getGitHubApi(feature);

    final String repositoryOwner = feature.getParameters().get(C.getRepositoryOwnerKey());
    final String repositoryName = feature.getParameters().get(C.getRepositoryNameKey());
    @Nullable final String context = feature.getParameters().get(C.getContextKey());
    final boolean addComments = !StringUtil.isEmptyOrSpaces(feature.getParameters().get(C.getUseCommentsKey()));
    final boolean useGuestUrls = !StringUtil.isEmptyOrSpaces(feature.getParameters().get(C.getUseGuestUrlsKey()));

    final GitHubApiReportEvent reportEvent = GitHubApiReportEvent.parse(feature.getParameters().get(C.getReportOnKey()));
    final boolean shouldReportOnStart = reportEvent == GitHubApiReportEvent.ON_START_AND_FINISH || reportEvent == GitHubApiReportEvent.ON_START;
    final boolean shouldReportOnFinish = reportEvent == GitHubApiReportEvent.ON_START_AND_FINISH || reportEvent == GitHubApiReportEvent.ON_FINISH;

    return new Handler() {
      @NotNull
      private String getViewResultsUrl(@NotNull final SRunningBuild build) {
        final String url = myWeb.getViewResultsUrl(build);
        if (useGuestUrls) {
          return url + (url.contains("?") ? "&" : "?") + "guest=1";
        }
        return url;
      }

      public boolean shouldReportOnStart() {
        return shouldReportOnStart;
      }

      public boolean shouldReportOnFinish() {
        return shouldReportOnFinish;
      }

      public void scheduleChangeStarted(@NotNull RepositoryVersion version, @NotNull SRunningBuild build) {
        scheduleChangeUpdate(version, build, "Started - TeamCity Build " + build.getFullName(), GitHubChangeState.Pending);
      }

      public void scheduleChangeCompeted(@NotNull RepositoryVersion version, @NotNull SRunningBuild build) {
        GitHubChangeState status = build.getStatusDescriptor().isSuccessful() ? GitHubChangeState.Success : GitHubChangeState.Error;
        String text = build.getStatusDescriptor().getText();
        if (text == null) {
          text = "";
        }
        scheduleChangeUpdate(version, build, text + " - TeamCity Build " + build.getFullName(), status);
      }

      private void scheduleChangeUpdate(@NotNull final RepositoryVersion version,
                                        @NotNull final SRunningBuild build,
                                        @NotNull final String message,
                                        @NotNull final GitHubChangeState status) {
        LOG.info("Scheduling GitHub status update for " +
                "hash: " + version.getVersion() + ", " +
                "branch: " + version.getVcsBranch() + ", " +
                "buildId: " + build.getBuildId() + ", " +
                "status: " + status);

        myExecutor.submit(ExceptionUtil.catchAll("set change status on github", new Runnable() {
          @NotNull
          private String getFriendlyDuration(final long seconds) {
            long second = seconds % 60;
            long minute = (seconds / 60) % 60;
            long hour = seconds / 60 / 60;

            return String.format("%02d:%02d:%02d", hour, minute, second);
          }

          @NotNull
          private String getComment(@NotNull RepositoryVersion version,
                                    @NotNull SRunningBuild build,
                                    boolean completed,
                                    @NotNull String hash) {
            final StringBuilder comment = new StringBuilder();

            if (completed) {
              comment.append("**").append(build.getStatusDescriptor().getStatus().getText()).append("**");
            } else {
              comment.append("Started");
            }

            comment.append(" - TeamCity ");
            final SBuildType bt = build.getBuildType();
            if (bt != null) {
              comment.append(bt.getFullName());
            }
            comment.append(" [Build ");
            comment.append(build.getBuildNumber());
            comment.append("](");
            comment.append(getViewResultsUrl(build));
            comment.append(") for ");
            comment.append(hash);
            comment.append("\n");

            if (completed) {
              ShortStatistics shortStatistics = build.getShortStatistics();
              comment.append("Tests: ");
              comment.append(shortStatistics.getAllTestCount());
              comment.append(", ");
              comment.append(shortStatistics.getFailedTestCount());
              comment.append(" failed (");
              comment.append(shortStatistics.getNewFailedCount());
              comment.append(" new), ");
              comment.append(shortStatistics.getIgnoredTestCount());
              comment.append(" ignored. ");
              comment.append("Build time: ");
              comment.append(getFriendlyDuration(build.getDuration()));

              if (build.getBuildStatus() != Status.NORMAL) {
                BuildStatisticsOptions options = new BuildStatisticsOptions(BuildStatisticsOptions.COMPILATION_ERRORS |
                        BuildStatisticsOptions.FIRST_FAILED_IN_BUILD | BuildStatisticsOptions.FIXED_IN_BUILD, 0);
                BuildStatistics buildStatistics = build.getBuildStatistics(options);

                List<BuildProblemData> failureReasons = build.getFailureReasons();
                for (BuildProblemData problemData : failureReasons) {
                  comment.append("\n\n");
                  comment.append(problemData.getDescription());

                  if (BuildProblemTypes.TC_COMPILATION_ERROR_TYPE.equals(problemData.getType())) {
                    List<CompilationBlockBean> compilationErrors = buildStatistics.getCompilationErrorBlocks();
                    for (int i = 0; i < compilationErrors.size(); i++) {
                      CompilationBlockBean compilationBlockBean = compilationErrors.get(i);
                      comment.append("\n* ");
                      comment.append(compilationBlockBean.getCompilerMessages());
                      if (i == 10) {
                        comment.append("\n* ... ");
                        comment.append(buildStatistics.getCompilationErrorsCount() - i);
                        comment.append(" more\n");
                      }
                    }
                  } else if (BuildProblemTypes.TC_FAILED_TESTS_TYPE.equals(problemData.getType())) {
                    final List<STestRun> failedTests = buildStatistics.getFailedTests();
                    final DateFormat dateFormat = SimpleDateFormat.getDateInstance(DateFormat.MEDIUM);
                    for (int i = 0; i < failedTests.size(); i++) {
                      comment.append("\n* ");
                      final STestRun testRun = failedTests.get(i);
                      if (testRun.isNewFailure())
                        comment.append("(new) ");
                      else if (testRun.getFirstFailed() != null) {
                        Date finishDate = testRun.getFirstFailed().getFinishDate();
                        comment.append("(since ");
                        comment.append(dateFormat.format(finishDate));
                        comment.append(") ");
                      }
                      comment.append(testRun.getTest().getName().toString());
                      comment.append("\n\n");
                      if (i == 10) {
                        comment.append("... ");
                        comment.append(buildStatistics.getFailedTestCount() - i);
                        comment.append(" more\n");
                        break;
                      }
                    }
                  }
                }
              }
            }

            return comment.toString();
          }

          @NotNull
          private String resolveCommitHash() {
            final String vcsBranch = version.getVcsBranch();
            if (vcsBranch != null && api.isPullRequestMergeBranch(vcsBranch)) {
              try {
                final String hash = api.findPullRequestCommit(repositoryOwner, repositoryName, vcsBranch);
                if (hash == null) {
                  throw new IOException("Failed to find head hash for commit from " + vcsBranch);
                }
                LOG.info("Resolved GitHub change commit for " + vcsBranch + " to point to pull request head for " +
                        "hash: " + version.getVersion() + ", " +
                        "newHash: " + hash + ", " +
                        "branch: " + version.getVcsBranch() + ", " +
                        "buildId: " + build.getBuildId() + ", " +
                        "status: " + status);
                return hash;
              } catch (IOException e) {
                LOG.warn("Failed to find status update hash for " + vcsBranch + " for repository " + repositoryName);
              }
            }
            return version.getVersion();
          }

          public void run() {
            final String hash = resolveCommitHash();
            try {
              api.setChangeStatus(
                      repositoryOwner,
                      repositoryName,
                      hash,
                      status,
                      getViewResultsUrl(build),
                      message,
                      context
              );
              LOG.info("Updated GitHub status for hash: " + hash + ", buildId: " + build.getBuildId() + ", status: " + status);
            } catch (IOException e) {
              LOG.warn("Failed to update GitHub status for hash: " + hash + ", buildId: " + build.getBuildId() + ", status: " + status + ". " + e.getMessage(), e);
            }
            if (addComments) {
              try {
                String pullRequestId = api.getPullRequestId(repositoryName, version.getVcsBranch());
                if (pullRequestId != null) {
                  try {
                    String pullRequestUrl = "https://github.com/" + repositoryOwner + "/" + repositoryName + "/pull/" + pullRequestId;
                    Set<SUser> committers = build.getCommitters(SelectPrevBuildPolicy.SINCE_LAST_BUILD).getUsers();
                    if (!committers.isEmpty()) {
                      build.setBuildComment(committers.iterator().next(), pullRequestUrl);
                    }
                  } catch (Exception e) {
                    LOG.warn("Error setting the build comment for " + pullRequestId, e);
                  }

                  api.postPullRequestComment(
                          repositoryOwner,
                          repositoryName,
                          pullRequestId,
                          getComment(version, build, status != GitHubChangeState.Pending, hash)
                  );
                  LOG.info("Added comment to GitHub pull request: " + pullRequestId + ", buildId: " + build.getBuildId() + ", status: " + status);
                } else {
                  api.postCommitComment(
                          repositoryOwner,
                          repositoryName,
                          hash,
                          getComment(version, build, status != GitHubChangeState.Pending, hash)
                  );
                  LOG.info("Added comment to GitHub commit: " + hash + ", buildId: " + build.getBuildId() + ", status: " + status);
                }
              } catch (Exception e) {
                LOG.warn("Failed add GitHub comment for branch: " + version.getVcsBranch() + ", buildId: " + build.getBuildId() + ", status: " + status + ". " + e.getMessage(), e);
              }
            }
          }
        }));
      }
    };
  }

  public static interface Handler {
    boolean shouldReportOnStart();
    boolean shouldReportOnFinish();
    void scheduleChangeStarted(@NotNull final RepositoryVersion hash, @NotNull final SRunningBuild build);
    void scheduleChangeCompeted(@NotNull final RepositoryVersion hash, @NotNull final SRunningBuild build);
  }
}
