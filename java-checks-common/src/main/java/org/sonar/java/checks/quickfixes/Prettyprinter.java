package org.sonar.java.checks.quickfixes;

import java.util.List;
import java.util.function.Consumer;
import org.sonar.java.checks.quickfixes.Ast.Expression;
import org.sonar.java.checks.quickfixes.Ast.HardCodedExpr;
import org.sonar.java.checks.quickfixes.Ast.HardCodedStat;
import org.sonar.java.checks.quickfixes.Ast.Statement;

import static org.sonar.java.checks.quickfixes.Ast.Block;
import static org.sonar.java.checks.quickfixes.Ast.IfStat;
import static org.sonar.java.checks.quickfixes.Ast.Visitor;

public final class Prettyprinter implements Visitor {
  private final PrettyprintStringBuilder pps;

  public static String prettyprint(Ast ast, FileConfig fileConfig) {
    PrettyprintStringBuilder pps = new PrettyprintStringBuilder(fileConfig);
    ast.accept(new Prettyprinter(pps));
    return pps.toString();
  }

  public Prettyprinter(PrettyprintStringBuilder pps) {
    this.pps = pps;
  }

  @Override
  public void visitVarDecl(Ast.VarDecl varDecl) {
    pps.add(varDecl.typeOrVar()).addSpace().add(varDecl.varName());
    varDecl.initializerExpr().ifPresent(initExpr -> {
      pps.add(" = ");
      initExpr.accept(this);
    });
  }

  @Override
  public void visitIfStat(IfStat ifStat) {
    pps.add("if (");
    ifStat.condition().accept(this);
    pps.add(") ");
    ifStat.thenBr().accept(this);
    var elseBrOpt = ifStat.elseBr();
    elseBrOpt.ifPresent(elseBr -> {
      pps.add(" else ");
      elseBr.accept(this);
    });
  }

  @Override
  public void visitBlock(Block block) {
    pps.add("{").incIndent().newLine();
    printStatsSeq(block.statements());
    pps.decIndent().newLine().add("}");
  }

  @Override
  public void visitHardCodedStat(HardCodedStat stat) {
    pps.add(stat.code().trim());
  }

  @Override
  public void visitHardCodedExpr(HardCodedExpr expr) {
    pps.add(expr.code().trim());
  }

  @Override
  public void visitSwitch(Ast.Switch swtch) {
    pps.add("switch (");
    swtch.scrutinee().accept(this);
    pps.add(") {").incIndent().newLine();
    printAllWithSep(swtch.cases(), PrettyprintStringBuilder::newLineIfNotEmpty);
    pps.decIndent().newLine().add("}");
  }

  @Override
  public void visitCase(Ast.Case caze) {
    caze.pattern().ifPresentOrElse(pattern -> {
      pps.add("case ");
      pattern.accept(this);
    }, () -> {
      pps.add("default");
    });
    pps.add(" -> ");
    caze.body().accept(this);
  }

  @Override
  public void visitValuePattern(Ast.ValuePattern valuePattern) {
    pps.add(valuePattern.value());
  }

  @Override
  public void visitGuardablePattern(Ast.GuardedPattern guardedPattern) {
    guardedPattern.pattern().accept(this);
    pps.add(" when ");
    guardedPattern.guardExpr().accept(this);
  }

  @Override
  public void visitVariablePattern(Ast.VariablePattern variablePattern) {
    pps.add(variablePattern.typeOrVar()).addSpace().add(variablePattern.varName());
  }

  @Override
  public void visitRecordPattern(Ast.RecordPattern recordPattern) {
    pps.add(recordPattern.recordName());
    if (!recordPattern.typeVars().isEmpty()) {
      pps.add("<").add(String.join(", ", recordPattern.typeVars())).add(">");
    }
    pps.add("(");
    printAllWithSep(recordPattern.fields(), PrettyprintStringBuilder::addComma);
    pps.add(")");
  }

  @Override
  public void visitBinop(Ast.BinaryOperator binop) {
    var parenthesizeLhs = binop.hasPrecedenceOver(binop.lhs());
    maybeParenthesize(binop.lhs(), parenthesizeLhs);
    pps.addSpace().add(binop.operator().code()).addSpace();
    var parenthesizeRhs = !binop.rhs().hasPrecedenceOver(binop) && !isSameAssociativeOperator(binop.rhs(), binop.operator());
    maybeParenthesize(binop.rhs(), parenthesizeRhs);
  }

  @Override
  public void visitAssignment(Ast.Assignment assignment) {
    assignment.lValue().accept(this);
    pps.addSpace().add(assignment.assigOp().code()).addSpace();
    assignment.newValue().accept(this);
  }

  @Override
  public void visitConst(Ast.Const cst) {
    var rawString = cst.value().toString();
    pps.add(cst.value() instanceof String ? ("\"" + rawString + "\"") : rawString);
  }

  private <T extends Ast> void printAllWithSep(List<T> asts, Consumer<PrettyprintStringBuilder> sep) {
    var iter = asts.iterator();
    while (iter.hasNext()) {
      iter.next().accept(this);
      if (iter.hasNext()) {
        sep.accept(pps);
      }
    }
  }

  private boolean isSameAssociativeOperator(Expression subExpr, Operator topLevelOp){
    return subExpr instanceof Ast.BinaryOperator subBinop && subBinop.operator() == topLevelOp && topLevelOp.isAssociative();
  }

  private void printStatsSeq(List<Statement> stats){
    var iter = stats.iterator();
    while (iter.hasNext()){
      var stat = iter.next();
      stat.accept(this);
      if (stat.requiresSemicolon() && !pps.endsWith(';')){
        pps.add(";");
      }
      if (iter.hasNext()){
        pps.newLine();
      }
    }
  }

  private void maybeParenthesize(Expression expr, boolean parenthesize) {
    if (parenthesize) {
      pps.add("(");
    }
    expr.accept(this);
    if (parenthesize) {
      pps.add(")");
    }
  }

}
