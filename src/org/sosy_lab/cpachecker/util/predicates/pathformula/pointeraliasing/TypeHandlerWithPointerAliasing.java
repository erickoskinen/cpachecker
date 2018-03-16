/*
 *  CPAchecker is a tool for configurable software verification.
 *  This file is part of CPAchecker.
 *
 *  Copyright (C) 2007-2014  Dirk Beyer
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
package org.sosy_lab.cpachecker.util.predicates.pathformula.pointeraliasing;

import static org.sosy_lab.cpachecker.util.predicates.pathformula.pointeraliasing.CTypeUtils.checkIsSimplified;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Multiset;
import java.util.HashMap;
import java.util.Map;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.ast.c.CRightHandSide;
import org.sosy_lab.cpachecker.cfa.ast.c.CSimpleDeclaration;
import org.sosy_lab.cpachecker.cfa.types.MachineModel;
import org.sosy_lab.cpachecker.cfa.types.c.CArrayType;
import org.sosy_lab.cpachecker.cfa.types.c.CComplexType.ComplexTypeKind;
import org.sosy_lab.cpachecker.cfa.types.c.CCompositeType;
import org.sosy_lab.cpachecker.cfa.types.c.CCompositeType.CCompositeTypeMemberDeclaration;
import org.sosy_lab.cpachecker.cfa.types.c.CElaboratedType;
import org.sosy_lab.cpachecker.cfa.types.c.CType;
import org.sosy_lab.cpachecker.cfa.types.c.CTypedefType;
import org.sosy_lab.cpachecker.util.predicates.pathformula.ctoformula.CtoFormulaTypeHandler;

public class TypeHandlerWithPointerAliasing extends CtoFormulaTypeHandler {

  private final MachineModel model;
  private final FormulaEncodingWithPointerAliasingOptions options;
  private final CachingCanonizingCTypeVisitor canonizingVisitor =
      new CachingCanonizingCTypeVisitor(true, true);

  /*
   * Use Multiset<String> instead of Map<String, Integer> because it is more
   * efficient. The integer value is stored as the number of instances of any
   * element in the Multiset. So instead of calling map.get(key) we just use
   * Multiset.count(key). This is better because the Multiset internally uses
   * modifiable integers instead of the immutable Integer class.
   */
  private final Multiset<CCompositeType> sizes = HashMultiset.create();
  private final Map<CCompositeType, ImmutableMap<String, Long>> offsets = new HashMap<>();

  public TypeHandlerWithPointerAliasing(
      LogManager pLogger,
      MachineModel pMachineModel,
      FormulaEncodingWithPointerAliasingOptions pOptions) {
    super(pLogger, pMachineModel);

    model = pMachineModel;
    options = pOptions;
  }

  /**
   * The method is used to speed up {@code sizeof} computation by caching sizes of declared composite types.
   * @param cType the type of which the size should be retrieved
   * @return The size of a given type.
   */
  @Override
  public int getSizeof(CType cType) {
    // Callers from inside this package should have simplified the type,
    // but callers from ctoformula package might have not.
    cType = simplifyType(cType);
    if (cType instanceof CCompositeType) {
      if (sizes.contains(cType)) {
        return sizes.count(cType);
      } else {
        int size = getSizeofUncached(cType);
        sizes.add((CCompositeType) cType, size);
        return size;
      }
    } else {
      return getSizeofUncached(cType);
    }
  }

  private int getSizeofUncached(CType cType) {
    if (cType instanceof CArrayType && cType.isIncomplete()) {
      CArrayType t = (CArrayType) cType;
      int length = t.getLengthAsInt().orElse(options.defaultArrayLength());
      final int sizeOfType = getSizeofUncached(t.getType());
      return length * sizeOfType;
    } else {
      return model.getSizeof(cType);
    }
  }

  /**
   * The method should be used everywhere the type of any expression is determined.
   * This is because the encoding uses types for naming of the UFs
   * as well as for over-approximating points-to sets (may-aliases).
   * To make the encoding precise enough the types should correspond to actually different types
   * (requiring explicit casts to be converted to one another),
   * so {@link CCompositeType}s, corresponding {@link CElaboratedType}s and {@link CTypedefType}s
   * shouldn't be distinguished and are converted to the same canonical type by this method.
   *
   * This method will also perform {@code const} and {@code volatile} modifiers elimination.
   *
   * Note that all code in this package should only use simplified types,
   * so calling this method should be only necessary when retrieving types from AST nodes.
   * Use {@link CTypeUtils#checkIsSimplified(CType)} as a precondition in other places
   * when you want to make sure a type is simplified.
   *
   * Also consider using one of the {@link #getSimplifiedType} overloads.
   *
   * @param type The type obtained from the CFA
   * @return The corresponding simplified canonical type
   */
  CType simplifyType(final CType type) {
    return type.accept(canonizingVisitor);
  }

  /** Get a simplified type as defined by {@link #simplifyType(CType)} from an AST node. */
  public CType getSimplifiedType(final CRightHandSide exp) {
    return simplifyType(exp.getExpressionType());
  }

  /**
   * Get a simplified type as defined by {@link #simplifyType(CType)} from a declaration.
   */
  CType getSimplifiedType(final CSimpleDeclaration decl) {
    return simplifyType(decl.getType());
  }

  /**
   * Get a simplified type as defined by {@link #simplifyType(CType)} from a field declaration.
   */
  CType getSimplifiedType(final CCompositeTypeMemberDeclaration field) {
    return simplifyType(field.getType());
  }

  /**
   * The method is used to speed up member offset computation for declared composite types.
   *
   * @param compositeType The composite type.
   * @param memberName The name of the member of the composite type.
   * @return The offset of the member in the composite type in bits.
   */
  long getBitOffset(CCompositeType compositeType, final String memberName) {
    checkIsSimplified(compositeType);
    assert compositeType.getKind() != ComplexTypeKind.ENUM : "Enums are not composite: " + compositeType;
    ImmutableMap<String, Long> multiset = offsets.get(compositeType);
    if (multiset == null) {
      Map<CCompositeTypeMemberDeclaration, Long> calculatedOffsets =
          machineModel.getAllFieldOffsetsInBits(compositeType);
      ImmutableMap.Builder<String, Long> memberOffsets =
          ImmutableMap.builderWithExpectedSize(calculatedOffsets.size());
      calculatedOffsets.forEach((key, value) -> memberOffsets.put(key.getName(), value));
      multiset = memberOffsets.build();
      offsets.put(compositeType, multiset);
    }
    return multiset.get(memberName);
  }
}
