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

import java.io.Serializable;
import java.util.Objects;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import org.sosy_lab.cpachecker.cfa.types.c.CType;

@Immutable
public final class PointerTarget implements Serializable {

  /** This constructor is for fields of nested structures and arrays */
  PointerTarget(
      String base, @Nullable CType containerType, long properOffset, long containerOffset) {
    this.base = base;
    this.containerType = containerType;
    this.properOffset = properOffset;
    this.containerOffset = containerOffset;
  }

  String getBase() {
    return base;
  }

  public String getBaseName() {
    return PointerTargetSet.getBaseName(base);
  }

  public long getOffset() {
    return containerOffset + properOffset;
  }

  long getProperOffset() {
    assert containerType != null : "The target's offset is ill-defined";
    return properOffset;
  }

  boolean isBase() {
    return containerType == null;
  }

  @Nullable
  CType getContainerType() {
    return containerType;
  }

  long getContainerOffset() {
    assert containerType != null : "The target's container offset is ill-defined";
    return containerOffset;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    } else if (!(other instanceof PointerTarget)) {
      return false;
    } else {
      final PointerTarget o = (PointerTarget) other;
      return properOffset == o.properOffset &&
             containerOffset == o.containerOffset &&
             base.equals(o.base) &&
             (containerType != null ?
                o.containerType != null && containerType.getCanonicalType().equals(o.containerType.getCanonicalType()) :
                o.containerType == null);
    }
  }

  @Override
  public int hashCode() {
    return Objects.hash(base, containerOffset, properOffset);
  }

  @Override
  public String toString() {
    return String.format("(Base: %s, type: %s, prop. offset: %d, cont. offset: %d)", base, containerType, properOffset, containerOffset);
  }

  final String base;
  final @Nullable CType containerType;
  final long properOffset;
  final long containerOffset;

  private static final long serialVersionUID = -1258065871533686442L;
}
