# 最近一次构建失败

时间：2026-09-11 11:19 UTC  
提交：`fa906b31c55c762169fd246becd733bf011ed9fd`

```
> Task :app:compileDebugKotlin FAILED
e: file:///home/runner/work/sports-widget/sports-widget/app/src/main/java/com/dailywork/sportswidget/MainActivity.kt:444:42 Type mismatch: inferred type is kotlin.text.StringBuilder /* = java.lang.StringBuilder */ but Context was expected
FAILURE: Build failed with an exception.
* What went wrong:
Execution failed for task ':app:compileDebugKotlin'.
org.gradle.api.tasks.TaskExecutionException: Execution failed for task ':app:compileDebugKotlin'.
Caused by: org.gradle.workers.internal.DefaultWorkerExecutor$WorkExecutionException: A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction
Caused by: org.jetbrains.kotlin.gradle.tasks.CompilationErrorException: Compilation error. See log for more details
	at org.jetbrains.kotlin.gradle.tasks.TasksUtilsKt.throwExceptionIfCompilationFailed(tasksUtils.kt:20)
	at org.jetbrains.kotlin.compilerRunner.GradleKotlinCompilerWork.run(GradleKotlinCompilerWork.kt:141)
	at org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction.execute(GradleCompilerRunnerWithWorkers.kt:73)
```
