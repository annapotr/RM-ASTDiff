package ASTDiff;

import gr.uom.java.xmi.decomposition.AbstractCodeMapping;
import matchers.Mapping;
import matchers.MultiMappingStore;
import org.eclipse.jdt.core.dom.ASTNode;
import tree.FakeTree;
import tree.Tree;
import tree.TreeUtils;

import java.util.HashMap;
import java.util.Iterator;

public class LeafMatcher extends BasicTreeMatcher implements TreeMatcher{
    @Override
    public void match(Tree src, Tree dst, AbstractCodeMapping abstractCodeMapping, MultiMappingStore mappingStore) {
//        if (true) return;
//        if (abstractCodeMapping.getFragment1().getLocationInfo().getStartLine() == 410)
//            System.out.println("");
        super.match(src,dst,abstractCodeMapping,mappingStore);
//        specialCases(src,dst,abstractCodeMapping,mappingStore);
    }

    private void specialCases(Tree src, Tree dst, AbstractCodeMapping abstractCodeMapping, MultiMappingStore mappingStore) {
        String EXP_STATEMENT =  "ExpressionStatement";
        String VAR_DEC_STATEMENT = "VariableDeclarationStatement";
        Tree expTree,varTree;
        boolean expFirst;
        Tree assignment_operator = null;
        Tree assignment,varFrag;
        assignment = varFrag = null;
        if (src.getType().name.equals(EXP_STATEMENT) && dst.getType().name.equals(VAR_DEC_STATEMENT))
        {
            expTree = src;
            varTree = dst;
            expFirst = true;
            if (varTree.getChildren().size() > 1)
            {
                varFrag = varTree.getChild(1);
            }
            if (expTree.getChildren().size() > 0)
            {
                if (expTree.getChild(0).getType().name.equals("Assignment"))
                {
                    assignment = expTree.getChild(0);
                    for(Tree child : assignment.getChildren())
                    {
                        if (child.getType().name.equals("ASSIGNMENT_OPERATOR") && child.getLabel().equals("="))
                        {
                            assignment_operator = child;
                            break;
                        }
                    }
                }
            }
        }
        else if (src.getType().name.equals(VAR_DEC_STATEMENT) && dst.getType().name.equals(EXP_STATEMENT))
        {
            expTree = dst;
            varTree = src;
            expFirst = false;
            if (varTree.getChildren().size() > 1)
            {
                varFrag = varTree.getChild(1);
            }
            if (expTree.getChildren().size() > 0)
            {
                if (expTree.getChild(0).getType().name.equals("Assignment"))
                {
                    assignment = expTree.getChild(0);
                    for(Tree child : assignment.getChildren())
                    {
                        if (child.getType().name.equals("ASSIGNMENT_OPERATOR") && child.getLabel().equals("="))
                        {
                            assignment_operator = child;
                            break;
                        }
                    }
                }
            }
        }
        else
        {
            //TODO : nothing for now;
            return;
        }
        if (expFirst)
        {
            mappingStore.addMapping(assignment,varFrag);
            mappingStore.addMapping(expTree,varTree);
            mappingStore.addMapping(assignment_operator, FakeTree.getInstance());
        }
        else {
            mappingStore.addMapping(varFrag,assignment);
            mappingStore.addMapping(varTree,expTree);
            mappingStore.addMapping(FakeTree.getInstance(),assignment_operator);
        }

    }

    private void leafMatcher(Tree src, Tree dst,MultiMappingStore mappingStore) {
//        super.match(src,dst,mappingStore);
    }
}


