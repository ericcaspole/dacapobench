/* Generated By:JJTree: Do not edit this line. ASTIfStatement.java Version 4.3 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=true,VISITOR=true,TRACK_TOKENS=false,NODE_PREFIX=AST,NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package net.sourceforge.pmd.lang.vm.ast;

public
class ASTIfStatement extends net.sourceforge.pmd.lang.vm.ast.AbstractVmNode {
  public ASTIfStatement(int id) {
    super(id);
  }

  public ASTIfStatement(VmParser p, int id) {
    super(p, id);
  }


  /** Accept the visitor. **/
  public Object jjtAccept(VmParserVisitor visitor, Object data) {
    return visitor.visit(this, data);
  }
}
/* JavaCC - OriginalChecksum=ebd6b7e40cf9cc5f55a4243217d264e8 (do not edit this line) */
