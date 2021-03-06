/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2018  Dirk Beyer
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
package org.sosy_lab.cpachecker.core.algorithm;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.Sets;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.logging.Level;
import org.sosy_lab.common.ShutdownManager;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.ConfigurationBuilder;
import org.sosy_lab.common.configuration.FileOption;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.ast.ADeclaration;
import org.sosy_lab.cpachecker.cfa.ast.AExpression;
import org.sosy_lab.cpachecker.cfa.ast.AFunctionCall;
import org.sosy_lab.cpachecker.cfa.ast.AIdExpression;
import org.sosy_lab.cpachecker.cfa.model.ADeclarationEdge;
import org.sosy_lab.cpachecker.cfa.model.AStatementEdge;
import org.sosy_lab.cpachecker.cfa.model.CFAEdge;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.cfa.types.Type;
import org.sosy_lab.cpachecker.cfa.types.c.CArrayType;
import org.sosy_lab.cpachecker.cfa.types.c.CCompositeType;
import org.sosy_lab.cpachecker.cfa.types.c.CCompositeType.CCompositeTypeMemberDeclaration;
import org.sosy_lab.cpachecker.cfa.types.c.CSimpleType;
import org.sosy_lab.cpachecker.cfa.types.c.CType;
import org.sosy_lab.cpachecker.cfa.types.java.JArrayType;
import org.sosy_lab.cpachecker.cfa.types.java.JSimpleType;
import org.sosy_lab.cpachecker.core.CPAcheckerResult.Result;
import org.sosy_lab.cpachecker.core.CoreComponentsFactory;
import org.sosy_lab.cpachecker.core.Specification;
import org.sosy_lab.cpachecker.core.interfaces.AbstractState;
import org.sosy_lab.cpachecker.core.interfaces.ConfigurableProgramAnalysis;
import org.sosy_lab.cpachecker.core.interfaces.Precision;
import org.sosy_lab.cpachecker.core.interfaces.StateSpacePartition;
import org.sosy_lab.cpachecker.core.interfaces.Statistics;
import org.sosy_lab.cpachecker.core.interfaces.StatisticsProvider;
import org.sosy_lab.cpachecker.core.reachedset.AggregatedReachedSets;
import org.sosy_lab.cpachecker.core.reachedset.ForwardingReachedSet;
import org.sosy_lab.cpachecker.core.reachedset.ReachedSet;
import org.sosy_lab.cpachecker.core.reachedset.UnmodifiableReachedSet;
import org.sosy_lab.cpachecker.exceptions.CPAEnabledAnalysisPropertyViolationException;
import org.sosy_lab.cpachecker.exceptions.CPAException;
import org.sosy_lab.cpachecker.util.CFATraversal;
import org.sosy_lab.cpachecker.util.CFATraversal.CFAVisitor;
import org.sosy_lab.cpachecker.util.CFATraversal.TraversalProcess;
import org.sosy_lab.cpachecker.util.LoopStructure;
import org.sosy_lab.cpachecker.util.Triple;
import org.sosy_lab.cpachecker.util.globalinfo.GlobalInfo;
import org.sosy_lab.cpachecker.util.resources.ResourceLimitChecker;
import org.sosy_lab.cpachecker.util.variableclassification.VariableClassification;

@Options(prefix = "heuristicSelection")
public class SelectionAlgorithm implements Algorithm, StatisticsProvider {

  private static class SelectionAlgorithmCFAVisitor implements CFAVisitor {

    private final HashSet<String> functionNames = new HashSet<>();
    private Set<String> arrayVariables = new HashSet<>();
    private HashSet<String> floatVariables = new HashSet<>();
    private int functionCount = 0;

    @Override
    public TraversalProcess visitEdge(CFAEdge pEdge) {
      switch (pEdge.getEdgeType()) {
        case StatementEdge:
          {
            final AStatementEdge edge = (AStatementEdge) pEdge;
            if (edge.getStatement() instanceof AFunctionCall) {
              final AFunctionCall call = (AFunctionCall) edge.getStatement();
              final AExpression exp = call.getFunctionCallExpression().getFunctionNameExpression();
              if (exp instanceof AIdExpression) {
                final AIdExpression id = (AIdExpression) exp;
                functionNames.add(id.getName());
                functionCount = functionCount + 1;
              }
            }
            break;
          }
        case DeclarationEdge:
          {
            final ADeclarationEdge declarationEdge = (ADeclarationEdge) pEdge;
            ADeclaration declaration = declarationEdge.getDeclaration();
            Type declType = declaration.getType();
            Queue<Type> types = new ArrayDeque<>();
            Set<Type> visitedTypes = new HashSet<>();
            types.add(declType);
            while (!types.isEmpty()) {
              Type type = types.poll();
              if (type instanceof CType) {
                type = ((CType) type).getCanonicalType();
              }
              if (visitedTypes.add(type)) {
                if (type instanceof CCompositeType) {
                  CCompositeType compositeType = (CCompositeType) type;
                  for (CCompositeTypeMemberDeclaration member : compositeType.getMembers()) {
                    types.offer(member.getType());
                  }
                }
                if (type instanceof CArrayType || type instanceof JArrayType) {
                  arrayVariables.add(declaration.getQualifiedName());
                } else if (type instanceof CSimpleType) {
                  CSimpleType simpleType = (CSimpleType) type;
                  if (simpleType.getType().isFloatingPointType()) {
                    floatVariables.add(declaration.getQualifiedName());
                  }
                } else if (type instanceof JSimpleType) {
                  JSimpleType simpleType = (JSimpleType) type;
                  if (simpleType.getType().isFloatingPointType()) {
                    floatVariables.add(declaration.getQualifiedName());
                  }
                }
              }
            }
            break;
          }
        case FunctionCallEdge:
        case FunctionReturnEdge:
        case CallToReturnEdge:
        default:
      }
      return TraversalProcess.CONTINUE;
    }

    @Override
    public TraversalProcess visitNode(CFANode pNode) {
      // TODO Auto-generated method stub
      return TraversalProcess.CONTINUE;
    }
  }

  private static class SelectionAlgorithmStatistics implements Statistics {

    private String chosenConfig = "";
    private int onlyRelevantBools = 0;
    private double relevantBoolRatio = 0.0;
    private double relevantAddressedRatio = 0.0;
    private int containsExternalFunctionCalls = 0;
    // TODO: Change name
    private int numberOfAllRightFunctions = 0;
    private int requiresAliasHandling = 0;
    private int requiresLoopHandling = 0;
    private int requiresCompositeTypeHandling = 0;
    private int requiresArrayHandling = 0;
    private int requiresFloatHandling = 0;

    @Override
    public String getName() {
      return "Selection Algorithm";
    }

    @Override
    public void printStatistics(PrintStream out, Result result, UnmodifiableReachedSet reached) {
      out.println("Used algorithm property:                       " + chosenConfig);
      out.println("Program containing only relevant bools:        " + onlyRelevantBools);
      out.println(
          String.format("Relevant boolean vars / relevant vars ratio:   %.4f", relevantBoolRatio));
      out.println("Requires alias handling:                       " + requiresAliasHandling);
      out.println("Requires loop handling:                        " + requiresLoopHandling);
      out.println(
          "Requires composite-type handling:              " + requiresCompositeTypeHandling);
      out.println("Requires array handling:                       " + requiresArrayHandling);
      out.println("Requires float handling:                       " + requiresFloatHandling);
      out.println(
          String.format(
              "Relevant addressed vars / relevant vars ratio: %.4f", relevantAddressedRatio));
      out.println(
          "Program containing external functions:         " + containsExternalFunctionCalls);
      out.println("Number of all righthand side functions:        " + numberOfAllRightFunctions);
      out.println();
    }
  }

  private final CFA cfa;
  private final ShutdownNotifier shutdownNotifier;
  private final Configuration globalConfig;
  private final Specification specification;
  private final LogManager logger;

  private Algorithm chosenAlgorithm;
  private final SelectionAlgorithmStatistics stats;

  @Option(secure = true, description = "Configuration for loop-free programs.")
  @FileOption(FileOption.Type.OPTIONAL_INPUT_FILE)
  private Path loopFreeConfig;

  @Option(secure = true, required = true, description = "Configuration for programs with loops.")
  @FileOption(FileOption.Type.OPTIONAL_INPUT_FILE)
  private Path loopConfig;

  @Option(
      secure = true,
      description = "Configuration for programs with loops and complex datastructures.")
  @FileOption(FileOption.Type.OPTIONAL_INPUT_FILE)
  private Path complexLoopConfig;

  @Option(
      secure = true,
      description = "Configuration for programs containing only relevant bool vars.")
  @FileOption(FileOption.Type.OPTIONAL_INPUT_FILE)
  private Path onlyBoolConfig;

  @Option(
      secure = true,
      description =
          "Configuration for programs containing more than @Option adressedRatio addressed vars.")
  @FileOption(FileOption.Type.OPTIONAL_INPUT_FILE)
  private Path addressedConfig;

  @Option(secure = true, description = "Configuration for programs containing composite types.")
  @FileOption(FileOption.Type.OPTIONAL_INPUT_FILE)
  private Path compositeTypeConfig;

  @Option(secure = true, description = "Configuration for programs containing arrays.")
  @FileOption(FileOption.Type.OPTIONAL_INPUT_FILE)
  private Path arrayConfig;

  @Option(
      secure = true,
      description =
          "Ratio of addressed vars. Values bigger than the passed value lead to @option addressedConfig.")
  private double addressedRatio = 0;

  public SelectionAlgorithm(
      CFA pCfa,
      ShutdownNotifier pShutdownNotifier,
      Configuration pConfig,
      Specification pSpecification,
      LogManager pLogger)
      throws InvalidConfigurationException {

    pConfig.inject(this);

    cfa = Objects.requireNonNull(pCfa);
    shutdownNotifier = Objects.requireNonNull(pShutdownNotifier);
    globalConfig = Objects.requireNonNull(pConfig);
    specification = Objects.requireNonNull(pSpecification);
    logger = Objects.requireNonNull(pLogger);

    stats = new SelectionAlgorithmStatistics();
  }

  @SuppressWarnings({"resource", "null"})
  @Override
  public AlgorithmStatus run(ReachedSet pReachedSet)
      throws CPAException, InterruptedException, CPAEnabledAnalysisPropertyViolationException {
    checkArgument(
        pReachedSet instanceof ForwardingReachedSet,
        "SelectionAlgorithm needs ForwardingReachedSet");
    checkArgument(
        pReachedSet.size() <= 1,
        "SelectionAlgorithm does not support being called several times with the same reached set");
    checkArgument(!pReachedSet.isEmpty(), "SelectionAlgorithm needs non-empty reached set");
    checkArgument(
        cfa.getVarClassification().isPresent(),
        "SelectionAlgorithm requires variable classification");

    // Preliminary analysis
    SelectionAlgorithmCFAVisitor visitor = new SelectionAlgorithmCFAVisitor();
    CFANode startingNode = cfa.getMainFunction();
    CFATraversal.dfs().traverseOnce(startingNode, visitor);
    for (String name : visitor.functionNames) {
      if (!cfa.getAllFunctionNames().contains(name)) {
        stats.containsExternalFunctionCalls = 1;
      }
    }
    stats.numberOfAllRightFunctions = visitor.functionCount;

    ForwardingReachedSet reached = (ForwardingReachedSet) pReachedSet;

    Optional<LoopStructure> loopStructure = cfa.getLoopStructure();
    VariableClassification variableClassification = cfa.getVarClassification().get();

    if (!variableClassification.getRelevantVariables().isEmpty()) {
      stats.relevantBoolRatio =
          ((double)
                  (Sets.intersection(
                          variableClassification.getIntBoolVars(),
                          variableClassification.getRelevantVariables())
                      .size()))
              / (double) (variableClassification.getRelevantVariables().size());

      stats.relevantAddressedRatio =
          ((double)
                  (Sets.intersection(
                          variableClassification.getAddressedVariables(),
                          variableClassification.getRelevantVariables())
                      .size()))
              / (double) (variableClassification.getRelevantVariables().size());
    }

    boolean hasOnlyRelevantIntBoolVars =
        variableClassification
            .getIntBoolVars()
            .containsAll(variableClassification.getRelevantVariables());

    boolean requiresAliasHandling =
        !variableClassification.getAddressedVariables().isEmpty()
            || !variableClassification.getAddressedFields().isEmpty();
    stats.requiresAliasHandling = requiresAliasHandling ? 1 : 0;

    boolean requiresLoopHandling =
        !loopStructure.isPresent() || !loopStructure.get().getAllLoops().isEmpty();
    stats.requiresLoopHandling = requiresLoopHandling ? 1 : 0;

    boolean requiresCompositeTypeHandling = !variableClassification.getRelevantFields().isEmpty();
    stats.requiresCompositeTypeHandling = requiresCompositeTypeHandling ? 1 : 0;

    boolean requiresArrayHandling =
        !Collections.disjoint(variableClassification.getRelevantVariables(), visitor.arrayVariables)
            || !Collections.disjoint(
                variableClassification.getAddressedFields().values(), visitor.arrayVariables);
    stats.requiresArrayHandling = requiresArrayHandling ? 1 : 0;

    boolean requiresFloatHandling =
        !Collections.disjoint(variableClassification.getRelevantVariables(), visitor.floatVariables)
            || !Collections.disjoint(
                variableClassification.getAddressedFields().values(), visitor.floatVariables);
    stats.requiresFloatHandling = requiresFloatHandling ? 1 : 0;

    stats.onlyRelevantBools = hasOnlyRelevantIntBoolVars ? 1 : 0;

    final Path chosenConfig;

    // Perform heuristic
    if (!requiresLoopHandling && loopFreeConfig != null) {
      // Run standard loop-free config
      chosenConfig = loopFreeConfig;
    } else if (hasOnlyRelevantIntBoolVars && onlyBoolConfig != null) {
      // Run bool only config
      chosenConfig = onlyBoolConfig;
    } else if (requiresCompositeTypeHandling && compositeTypeConfig != null) {
      chosenConfig = compositeTypeConfig;
    } else if (stats.relevantAddressedRatio > addressedRatio && addressedConfig != null) {
      chosenConfig = addressedConfig;
    } else if (requiresArrayHandling && arrayConfig != null) {
      chosenConfig = arrayConfig;
    } else if ((requiresFloatHandling || requiresArrayHandling || requiresCompositeTypeHandling)
        && complexLoopConfig != null) {
      // Run complex loop config
      chosenConfig = complexLoopConfig;
    } else {
      // Run standard loop config
      chosenConfig = loopConfig;
    }

    stats.chosenConfig = chosenConfig.toString().substring(7);

    Triple<Algorithm, ConfigurableProgramAnalysis, ReachedSet> currentAlg;
    ShutdownManager shutdownManager = ShutdownManager.createWithParent(shutdownNotifier);
    try {
      currentAlg = createAlgorithm(chosenConfig, cfa.getMainFunction(), shutdownManager);
    } catch (InvalidConfigurationException e) {
      logger.logUserException(
          Level.WARNING,
          e,
          "Skipping one analysis because the configuration file "
              + chosenConfig.toString()
              + " is invalid");
      return AlgorithmStatus.UNSOUND_AND_PRECISE;
    } catch (IOException e) {
      String message =
          "Skipping one analysis because the configuration file "
              + chosenConfig.toString()
              + " could not be read";
      if (shutdownNotifier.shouldShutdown() && e instanceof ClosedByInterruptException) {
        logger.log(Level.WARNING, message);
      } else {
        logger.logUserException(Level.WARNING, e, message);
      }
      return AlgorithmStatus.UNSOUND_AND_PRECISE;
    }

    chosenAlgorithm = currentAlg.getFirst();
    // ConfigurableProgramAnalysis chosenCpa = currentAlg.getSecond();
    ReachedSet reachedSetForChosenAnalysis = currentAlg.getThird();

    reached.setDelegate(reachedSetForChosenAnalysis);

    return chosenAlgorithm.run(reachedSetForChosenAnalysis);
  }

  private Triple<Algorithm, ConfigurableProgramAnalysis, ReachedSet> createAlgorithm(
      Path singleConfigFileName, CFANode mainFunction, ShutdownManager singleShutdownManager)
      throws InvalidConfigurationException, CPAException, IOException, InterruptedException {

    ReachedSet reached;
    ConfigurableProgramAnalysis cpa;
    Algorithm algorithm;

    ConfigurationBuilder singleConfigBuilder = Configuration.builder();
    singleConfigBuilder.copyFrom(globalConfig);
    singleConfigBuilder.clearOption("analysis.selectAnalysisHeuristically");
    singleConfigBuilder.loadFromFile(singleConfigFileName);

    Configuration singleConfig = singleConfigBuilder.build();
    LogManager singleLogger = logger.withComponentName("Analysis " + singleConfigFileName);

    ResourceLimitChecker singleLimits =
        ResourceLimitChecker.fromConfiguration(singleConfig, singleLogger, singleShutdownManager);
    singleLimits.start();

    AggregatedReachedSets aggregateReached = new AggregatedReachedSets();

    CoreComponentsFactory coreComponents =
        new CoreComponentsFactory(
            singleConfig, singleLogger, singleShutdownManager.getNotifier(), aggregateReached);
    cpa = coreComponents.createCPA(cfa, specification);

    GlobalInfo.getInstance().setUpInfoFromCPA(cpa);

    algorithm = coreComponents.createAlgorithm(cpa, cfa, specification);

    reached = createInitialReachedSetForRestart(cpa, mainFunction, coreComponents, singleLogger);

    return Triple.of(algorithm, cpa, reached);
  }

  private ReachedSet createInitialReachedSetForRestart(
      ConfigurableProgramAnalysis cpa,
      CFANode mainFunction,
      CoreComponentsFactory pFactory,
      LogManager singleLogger)
      throws InterruptedException {
    singleLogger.log(Level.FINE, "Creating initial reached set");

    AbstractState initialState =
        cpa.getInitialState(mainFunction, StateSpacePartition.getDefaultPartition());
    Precision initialPrecision =
        cpa.getInitialPrecision(mainFunction, StateSpacePartition.getDefaultPartition());

    ReachedSet reached = pFactory.createReachedSet();
    reached.add(initialState, initialPrecision);
    return reached;
  }

  @Override
  public void collectStatistics(Collection<Statistics> pStatsCollection) {
    if (chosenAlgorithm instanceof StatisticsProvider) {
      ((StatisticsProvider) chosenAlgorithm).collectStatistics(pStatsCollection);
    }
    pStatsCollection.add(stats);
  }
}
