/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2013  Dirk Beyer
 *  All rights reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 *  CPAchecker web page:
 *    http://cpachecker.sosy-lab.org
 */
package org.sosy_lab.cpachecker.core.algorithm.testgen;

import java.io.IOException;
import java.io.Writer;
import java.util.Collection;
import java.util.logging.Level;

import org.sosy_lab.common.LogManager;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.io.Files;
import org.sosy_lab.common.io.Path;
import org.sosy_lab.common.io.Paths;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.core.CPABuilder;
import org.sosy_lab.cpachecker.core.ShutdownNotifier;
import org.sosy_lab.cpachecker.core.algorithm.Algorithm;
import org.sosy_lab.cpachecker.core.algorithm.testgen.analysis.BasicTestGenPathAnalysisStrategy;
import org.sosy_lab.cpachecker.core.algorithm.testgen.analysis.TestGenPathAnalysisStrategy;
import org.sosy_lab.cpachecker.core.algorithm.testgen.iteration.IterationStrategyFactory;
import org.sosy_lab.cpachecker.core.algorithm.testgen.model.PredicatePathAnalysisResult;
import org.sosy_lab.cpachecker.core.algorithm.testgen.model.TestGenIterationStrategy;
import org.sosy_lab.cpachecker.core.algorithm.testgen.model.TestGenIterationStrategy.IterationModel;
import org.sosy_lab.cpachecker.core.interfaces.AbstractState;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysis;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;
import org.sosy_lab.cpachecker.core.interfaces.StatisticsProvider;
import org.sosy_lab.cpachecker.core.reachedset.ReachedSet;
import org.sosy_lab.cpachecker.core.reachedset.ReachedSetFactory;
import org.sosy_lab.cpachecker.cpa.arg.ARGPath;
import org.sosy_lab.cpachecker.cpa.arg.ARGState;
import org.sosy_lab.cpachecker.cpa.arg.ARGUtils;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.cpachecker.exceptions.PredicatedAnalysisPropertyViolationException;
import org.sosy_lab.cpachecker.util.predicates.FormulaManagerFactory;
import org.sosy_lab.cpachecker.util.predicates.PathChecker;
import org.sosy_lab.cpachecker.util.predicates.Solver;
import org.sosy_lab.cpachecker.util.predicates.interfaces.PathFormulaManager;
import org.sosy_lab.cpachecker.util.predicates.interfaces.view.FormulaManagerView;
import org.sosy_lab.cpachecker.util.predicates.pathformula.PathFormulaManagerImpl;

import com.google.common.base.Joiner;

@Options(prefix = "testgen")
public class TestGenAlgorithm implements Algorithm, StatisticsProvider {

  StartupConfig startupConfig;
  private LogManager logger;

  public enum IterationStrategySelector {
    AUTOMATON_CONTROLLED,
    SAME_ALGORITHM_RESTART,
    SAME_ALGORITHM_FILTER_WAITLIST
  }

  @Option(name = "iterationStrategy", description = "Selects the iteration Strategy for TestGenAlgorithm")
  private IterationStrategySelector iterationStrategySelector = IterationStrategySelector.AUTOMATON_CONTROLLED;

  @Option(
      name = "stopOnError",
      description = "algorithm stops on first found error path. Otherwise the algorithms tries to reach 100% coverage")
  private boolean stopOnError = false;

  private CFA cfa;
  private ConfigurableProgramAnalysis cpa;


  private TestGenIterationStrategy iterationStrategy;
  private TestGenPathAnalysisStrategy analysisStrategy;
  private TestCaseSet testCaseSet;

  private TestGenStatistics stats;
  private int reachedSetCounter = 0;


  //  ConfigurationBuilder singleConfigBuilder = Configuration.builder();
  //  singleConfigBuilder.copyFrom(globalConfig);
  //  singleConfigBuilder.clearOption("restartAlgorithm.configFiles");
  //  singleConfigBuilder.clearOption("analysis.restartAfterUnknown");


  public TestGenAlgorithm(Algorithm pAlgorithm, ConfigurableProgramAnalysis pCpa,
      ShutdownNotifier pShutdownNotifier, CFA pCfa,
      Configuration pConfig, LogManager pLogger, CPABuilder pCpaBuilder) throws InvalidConfigurationException,
      CPAException {

    startupConfig = new StartupConfig(pConfig, pLogger, pShutdownNotifier);
    startupConfig.getConfig().inject(this);
    stats = new TestGenStatistics(iterationStrategySelector == IterationStrategySelector.AUTOMATON_CONTROLLED);

    cfa = pCfa;
    cpa = pCpa;
    this.logger = pLogger;
    testCaseSet = new TestCaseSet();
    FormulaManagerFactory formulaManagerFactory =
        new FormulaManagerFactory(startupConfig.getConfig(), pLogger,
            ShutdownNotifier.createWithParent(pShutdownNotifier));
    FormulaManagerView formulaManager =
        new FormulaManagerView(formulaManagerFactory.getFormulaManager(), startupConfig.getConfig(), logger);
    PathFormulaManager pfMgr = new PathFormulaManagerImpl(formulaManager, startupConfig.getConfig(), logger, cfa);
    Solver solver = new Solver(formulaManager, formulaManagerFactory);
    PathChecker pathChecker = new PathChecker(pLogger, pfMgr, solver);
    iterationStrategy = new IterationStrategyFactory(startupConfig, cfa, new ReachedSetFactory(startupConfig.getConfig(), logger), pCpaBuilder, stats).createStrategy(iterationStrategySelector, pAlgorithm);
    analysisStrategy = new BasicTestGenPathAnalysisStrategy(pathChecker, stats);
  }


  @Override
  public boolean run(ReachedSet pReachedSet) throws CPAException, InterruptedException,
      PredicatedAnalysisPropertyViolationException {

    stats.getTotalTimer().start();
    PredicatePathAnalysisResult lastResult = PredicatePathAnalysisResult.INVALID;
    iterationStrategy.initializeModel(pReachedSet);
    long loopCounter = 0;


    while (true /*globalReached.hasWaitingState()*/) {
      logger.logf(Level.INFO, "TestGen iteration %d", loopCounter++);
      //explicit, DFS or DFSRAND, PRECISION=TRACK_ALL; with automaton of new path created in previous iteration OR custom CPA
      boolean sound = iterationStrategy.runAlgorithm();
      //sound should normally be unsound for us.

      if (!(iterationStrategy.getLastState() instanceof ARGState)) { throw new IllegalStateException(
          "wrong configuration of explicit cpa, because concolicAlg needs ARGState"); }
      /*
       * check if reachedSet contains a target (error) state.
       */
      ARGState pseudoTarget = (ARGState) iterationStrategy.getLastState();
      if (pseudoTarget.isTarget()) {
        if (stopOnError) {
          updateGlobalReached();
          stats.getTotalTimer().stop();
          return true;
        }else{
          testCaseSet.addTarget(pseudoTarget);
        //TODO add error to errorpathlist
        }
      }
      /*
       * not an error path. selecting new path to traverse.
       */

      ARGPath executedPath = ARGUtils.getOnePathTo(pseudoTarget);
      testCaseSet.addExecutedPath(executedPath);
      logger.log(Level.INFO, "Starting predicate path check...");
      PredicatePathAnalysisResult result = analysisStrategy.findNewFeasiblePathUsingPredicates(executedPath);
      logger.log(Level.INFO, "Starting predicate path check DONE");

      dumpReachedAndARG(iterationStrategy.getModel().getLocalReached());

      if (result.isEmpty()) {
        /*
         * we reached all variations (identified by predicates) of the program path.
         * If we didn't find an error, the program is safe and sound, in the sense of a concolic test.
         * TODO: Identify the problems with soundness in context on concolic testing
         */
        updateGlobalReached();
        stats.getTotalTimer().stop();
        return true; //true = sound or false = unsound. Which case is it here??
      }
      /*
       * symbolic analysis of the path conditions returned a new feasible path (or a new model)
       * the next iteration. Creating automaton to guide next iteration.
       */
      iterationStrategy.updateIterationModelForNextIteration(result);

      lastResult = result;
    }
  }

  private void updateGlobalReached() {
    IterationModel model = iterationStrategy.getModel();
    model.getGlobalReached().clear();
    for (AbstractState state : model.getLocalReached()) {
      model.getGlobalReached().add(state, model.getLocalReached().getPrecision(state));
      model.getGlobalReached().removeOnlyFromWaitlist(state);
    }

  }


  @Override
  public void collectStatistics(Collection<Statistics> pStatsCollection) {
    pStatsCollection.add(stats);
  }

  private void dumpReachedAndARG(ReachedSet pReached) {
    Path reachedFile = Paths.get("output/reachedsets/reached" + reachedSetCounter + ".txt");

    try (Writer w = Files.openOutputFile(reachedFile)) {
      Joiner.on('\n').appendTo(w, pReached);
    } catch (IOException e) {
      logger.logUserException(Level.WARNING, e, "Could not write reached set to file");
    } catch (OutOfMemoryError e) {
      logger.logUserException(Level.WARNING, e,
          "Could not write reached set to file due to memory problems");
    }

    Path argFile = Paths.get("output/args/arg" + reachedSetCounter + ".dot");

    try (Writer w = Files.openOutputFile(argFile)) {
      ARGUtils.writeARGAsDot(w, (ARGState) pReached.getFirstState());
    } catch (IOException e) {
      logger.logUserException(Level.WARNING, e, "Could not write ARG to file");
    }

    reachedSetCounter++;
  }

  protected class TestCaseSet{
    public void addTarget(AbstractState target){
      //FIXME
    }
    public void addExecutedPath(ARGPath path){
      //FIXME
    }
  }
}
