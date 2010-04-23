package cpa.observeranalysis;

import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.cdt.core.dom.ast.IASTNode;

import cfa.objectmodel.CFALabelNode;
import cfa.objectmodel.CFANode;
import cpa.common.interfaces.AbstractElement;
import cpa.common.interfaces.AbstractQueryableElement;
import exceptions.InvalidQueryException;

/**
 * Implements a boolean expression that evaluates and returns a <code>MaybeBoolean</code> value when <code>eval()</code> is called.
 * The Expression can be evaluated multiple times.
 * @author rhein
 */
abstract class ObserverBoolExpr {
  
  /**
   * @author rhein
   * This is a extension of the boolean data type. It also contains a dont-know-value (MAYBE).
   */
  static enum MaybeBoolean {TRUE, FALSE, MAYBE;
    static MaybeBoolean valueOf(boolean pB) {
      return pB ? TRUE : FALSE;
    }
  }
  
  private ObserverBoolExpr() {} //nobody can use this
  
  public abstract MaybeBoolean eval(ObserverExpressionArguments pArgs);
  
  
  /**
   * Implements a regex match on the label after the current CFAEdge.
   * The eval method returns false if there is no label following the CFAEdge.
   * (".*" in java-regex means "any characters")
   * @author rhein
   */
  static class MatchLabelRegEx extends ObserverBoolExpr {
    
    private final Pattern pattern;
    
    public MatchLabelRegEx(String pPattern) {
      pattern = Pattern.compile(pPattern);
    }
    
    @Override
    public MaybeBoolean eval(ObserverExpressionArguments pArgs) {
      CFANode successorNode = pArgs.getCfaEdge().getSuccessor();
      if (successorNode instanceof CFALabelNode) {
        String label = ((CFALabelNode)successorNode).getLabel().toLowerCase();
        return MaybeBoolean.valueOf(pattern.matcher(label).matches());

      } else {
        return MaybeBoolean.FALSE;
      }
    }
    
    @Override
    public String toString() {
      return "MATCH LABEL [" + pattern + "]";
    }
  }
  
  
  /**
   * This is a efficient implementation of the ASTComparison (it caches the generated ASTs for the pattern).
   * It also displays error messages if the AST contains problems/errors.
   * The AST Comparison evaluates the pattern (coming from the Automaton Definition) and the C-Statement on the CFA Edge to ASTs and compares these with a Tree comparison algorithm.
   * @author rhein
   */
  static class MatchCFAEdgeASTComparison extends ObserverBoolExpr {
    
    private final IASTNode patternAST;
    
    public MatchCFAEdgeASTComparison(String pPattern) throws InvalidAutomatonException {
      this.patternAST = ObserverASTComparator.generatePatternAST(pPattern);
    }
    
    @Override
    public MaybeBoolean eval(ObserverExpressionArguments pArgs) {
     
      IASTNode ast = pArgs.getCfaEdge().getRawAST();
      if (ast != null) {
        // some edges do not have an AST node attached to them, e.g. BlankEdges 
        return MaybeBoolean.valueOf(ObserverASTComparator.compareASTs(ast, patternAST, pArgs));
      }
      
      return MaybeBoolean.FALSE;
    }
    
    @Override
    public String toString() {
      return "MATCH {" + patternAST.getRawSignature() + "}";
    }
  }
  
  
  static class MatchCFAEdgeRegEx extends ObserverBoolExpr {
    
    private final Pattern pattern;
    
    public MatchCFAEdgeRegEx(String pPattern) {
      pattern = Pattern.compile(pPattern);
    }
    
    @Override
    public MaybeBoolean eval(ObserverExpressionArguments pArgs) {
      return MaybeBoolean.valueOf(
          pattern.matcher(pArgs.getCfaEdge().getRawStatement()).matches());
    }
    
    @Override
    public String toString() {
      return "MATCH [" + pattern + "]";
    }
  }

  
  static class MatchCFAEdgeExact extends ObserverBoolExpr {
    
    private final String pattern;
    
    public MatchCFAEdgeExact(String pPattern) {
      pattern = pPattern;
    }

    @Override
    public MaybeBoolean eval(ObserverExpressionArguments pArgs) {
      return MaybeBoolean.valueOf(
          pArgs.getCfaEdge().getRawStatement().equals(pattern));
    }
    
    @Override
    public String toString() {
      return "MATCH \"" + pattern + "\"";
    }
  }
  
  
  /**
   * Sends a query-String to an <code>AbstractElement</code> of another analysis and returns the query-Result.  
   * @author rhein
   */
  static class CPAQuery extends ObserverBoolExpr {
    private final String cpaName;
    private final String queryString;
    
    // the pattern \$\d+ matches Expressions like $1 $2 $3
    private static Pattern TRANSITION_VARS_PATTERN = Pattern.compile("\\$\\d+");
    
    // the pattern \$\d+ matches Expressions like $$x $$y23rinnksd $observerVar (all terminated by a non-word-character)
    private static Pattern OBSERVER_VARS_PATTERN = Pattern.compile("\\$[a-zA-Z]\\w*");
    
    public CPAQuery(String pCPAName, String pQuery) {
      cpaName = pCPAName;
      queryString = pQuery;
    }

    @Override
    public MaybeBoolean eval(ObserverExpressionArguments pArgs) {
      // replace transition variables
      String modifiedQueryString = replaceVariables(pArgs, queryString);
      if (modifiedQueryString == null) {
        return MaybeBoolean.MAYBE;
      }
      
      for (AbstractElement ae : pArgs.getAbstractElements()) {
        if (ae instanceof AbstractQueryableElement) {
          AbstractQueryableElement aqe = (AbstractQueryableElement) ae;
          if (aqe.getCPAName().equals(cpaName)) {
            try {
              return MaybeBoolean.valueOf(aqe.checkProperty(modifiedQueryString));
              
            } catch (InvalidQueryException e) {
              pArgs.getLogger().logException(Level.WARNING, e, 
                  "ObserverAutomaton encountered an Exception during Query of the " 
                  + cpaName + " CPA on Edge " + pArgs.getCfaEdge().getRawStatement());
              return MaybeBoolean.FALSE;
            }
          }
        }
      }
      return MaybeBoolean.MAYBE; // the necessary CPA-State was not found
    }
    
    /**
     * This method replaces all references to
     * 1. ObserverVariables (referenced by $$<Name-of-Variable>)
     * 2. TransitionVariables (referenced by $<Number-of-Variable>)
     * with the values of the variables.
     * If the variable is not found the function returns null.
     * @param pArgs
     * @param pQueryString
     * @return
     */
    static String replaceVariables (
        ObserverExpressionArguments pArgs, String pQueryString) {
      
      // replace references to Transition Variables
      Matcher matcher = TRANSITION_VARS_PATTERN.matcher(pQueryString);
      StringBuffer result = new StringBuffer();
      while (matcher.find()) {
        matcher.appendReplacement(result, "");
        String key = pQueryString.substring(matcher.start()+1, matcher.end()); // matched string startswith $
        try {
          int varKey = Integer.parseInt(key);
          String var = pArgs.getTransitionVariable(varKey);
          if (var == null) {
            // this variable has not been set.
            pArgs.getLogger().log(Level.WARNING, "could not replace the transition variable $" + varKey + " (not found).");
            return null;
          } else {
            result.append(var);
          }
        } catch (NumberFormatException e) {
          pArgs.getLogger().log(Level.WARNING, "could not parse the int in " + matcher.group() + " , leaving it untouched");
          result.append(matcher.group());
        }
      }
      matcher.appendTail(result);
      
      // replace references to observer Variables
      matcher = OBSERVER_VARS_PATTERN.matcher(result.toString());
      result = new StringBuffer();
      while (matcher.find()) {
        matcher.appendReplacement(result, "");
        String varName = pQueryString.substring(matcher.start()+2, matcher.end()); // matched string starts with $$
        ObserverVariable variable = pArgs.getObserverVariables().get(varName);
        if (variable == null) {
          // this variable has not been set.
          pArgs.getLogger().log(Level.WARNING, "could not replace the Observer variable reference " + varName + " (not found).");
          return null;
        } else {
          result.append(variable.getValue());
        }
      }
      matcher.appendTail(result);
      return result.toString();
    }
    
    @Override
    public String toString() {
      return "CHECK(" + cpaName + "(\"" + queryString + "\"))";
    }
  }
  
  
  /** Constant for true.
   * @author rhein
   */
  static class True extends ObserverBoolExpr {
    @Override
    public MaybeBoolean eval(ObserverExpressionArguments pArgs) {
      return MaybeBoolean.TRUE;
    }
    
    @Override
    public String toString() {
      return "TRUE";
    }
  }
  
  
  /** Constant for false.
   * @author rhein
   */
  static class False extends ObserverBoolExpr {
    @Override
    public MaybeBoolean eval(ObserverExpressionArguments pArgs) {
      return MaybeBoolean.FALSE;
    }
    
    @Override
    public String toString() {
      return "FALSE";
    }
  }
  
  
  /** Tests the equality of the values of two instances of {@link ObserverIntExpr}.
   * @author rhein
   */
  static class IntEqTest extends ObserverBoolExpr {

    private final ObserverIntExpr a;
    private final ObserverIntExpr b;
    
    public IntEqTest(ObserverIntExpr pA, ObserverIntExpr pB) {
      this.a = pA;
      this.b = pB;
    }
    
    @Override
    public MaybeBoolean eval(ObserverExpressionArguments pArgs) {
      return MaybeBoolean.valueOf(a.eval(pArgs) == b.eval(pArgs));
    }
    
    @Override
    public String toString() {
      return a + " == " + b;
    }
  }
  
  
  /** Tests whether two instances of {@link ObserverIntExpr} evaluate to different integers.
   * @author rhein
   */
  static class IntNotEqTest extends ObserverBoolExpr {
    
    private final ObserverIntExpr a;
    private final ObserverIntExpr b;
    
    public IntNotEqTest(ObserverIntExpr pA, ObserverIntExpr pB) {
      this.a = pA;
      this.b = pB;
    }
    
    public @Override MaybeBoolean eval(ObserverExpressionArguments pArgs) { 
      return MaybeBoolean.valueOf(a.eval(pArgs) != b.eval(pArgs));
    }
    
    @Override
    public String toString() {
      return a + " != " + b;
    }
  }
  
  
  /** Computes the disjunction of two {@link ObserverBoolExpr} (lazy evaluation).
   * @author rhein
   */
  static class Or extends ObserverBoolExpr {
    
    private final ObserverBoolExpr a;
    private final ObserverBoolExpr b;
    
    public Or(ObserverBoolExpr pA, ObserverBoolExpr pB) {
      this.a = pA;
      this.b = pB;
    }
    
    public @Override MaybeBoolean eval(ObserverExpressionArguments pArgs) { 
      MaybeBoolean resultA = a.eval(pArgs);
      if (resultA == MaybeBoolean.TRUE) {
        return MaybeBoolean.TRUE;
      } else {
        MaybeBoolean resultB = b.eval(pArgs);
        if (resultB == MaybeBoolean.TRUE)  return MaybeBoolean.TRUE;
        if (resultB == MaybeBoolean.FALSE) return resultA;
        return resultB; // in this case resultB==MAYBE
      }
    }
    
    @Override
    public String toString() {
      return "(" + a + " || " + b + ")";
    }
  }
  
  
  /** Computes the conjunction of two {@link ObserverBoolExpr} (lazy evaluation).
   * @author rhein
   */
  static class And extends ObserverBoolExpr {
    
    private final ObserverBoolExpr a;
    private final ObserverBoolExpr b;
    
    public And(ObserverBoolExpr pA, ObserverBoolExpr pB) {
      this.a = pA;
      this.b = pB;
    }
    
    @Override
    public MaybeBoolean eval(ObserverExpressionArguments pArgs) { 
      MaybeBoolean resultA = a.eval(pArgs);
      if (resultA == MaybeBoolean.FALSE) {
        return MaybeBoolean.FALSE;
      } else {
        MaybeBoolean resultB = b.eval(pArgs);
        if (resultB == MaybeBoolean.FALSE)  return MaybeBoolean.FALSE;
        if (resultB == MaybeBoolean.TRUE) return resultA;
        return resultB; // in this case resultB==MAYBE
      }
    }
    
    @Override
    public String toString() {
      return "(" + a + " && " + b + ")";
    }
  }

  
  /**
   * Negates the result of a {@link ObserverBoolExpr}. If the result is MAYBE it is returned unchanged.
   * @author rhein
   */
  static class Negation extends ObserverBoolExpr {

    private final ObserverBoolExpr a;
    
    public Negation(ObserverBoolExpr pA) {
      this.a = pA;
    }
    
    @Override
    public MaybeBoolean eval(ObserverExpressionArguments pArgs) { 
      MaybeBoolean resultA = a.eval(pArgs);
      switch (resultA) {
      case TRUE: return MaybeBoolean.FALSE; 
      case FALSE: return MaybeBoolean.TRUE;
      default: return MaybeBoolean.MAYBE;
      }
    }
    
    @Override
    public String toString() {
      return "!" + a;
    }
  }

  
  /**
   * Boolean Equality
   * @author rhein
   */
  static class BoolEqTest extends ObserverBoolExpr {
    
    private final ObserverBoolExpr a;
    private final ObserverBoolExpr b;
    
    public BoolEqTest(ObserverBoolExpr pA, ObserverBoolExpr pB) {
      this.a = pA;
      this.b = pB;
    }
    
    @Override
    public MaybeBoolean eval(ObserverExpressionArguments pArgs) { 
      MaybeBoolean resultA = a.eval(pArgs);
      MaybeBoolean resultB = b.eval(pArgs);
      if (resultA == MaybeBoolean.MAYBE || resultB == MaybeBoolean.MAYBE) {
        return MaybeBoolean.MAYBE;
      } else {
        return MaybeBoolean.valueOf(resultA.equals(resultB));
      }
    }
    
    @Override
    public String toString() {
      return a + " == " + b;
    }
  }
  
  
  /**
   * Boolean !=
   * @author rhein
   */
  static class BoolNotEqTest extends ObserverBoolExpr {

    private final ObserverBoolExpr a;
    private final ObserverBoolExpr b;
    
    public BoolNotEqTest(ObserverBoolExpr pA, ObserverBoolExpr pB) {
      this.a = pA;
      this.b = pB;
    }
    
    @Override 
    public MaybeBoolean eval(ObserverExpressionArguments pArgs) { 
      MaybeBoolean resultA = a.eval(pArgs);
      MaybeBoolean resultB = b.eval(pArgs);
      if (resultA == MaybeBoolean.MAYBE || resultB == MaybeBoolean.MAYBE) {
        return MaybeBoolean.MAYBE;
      } else {
        return MaybeBoolean.valueOf(! resultA.equals(resultB));
      }
    }
    
    @Override
    public String toString() {
      return a + " != " + b;
    }
  }
}
