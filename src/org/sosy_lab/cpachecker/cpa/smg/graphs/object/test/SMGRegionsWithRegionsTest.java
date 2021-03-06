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
package org.sosy_lab.cpachecker.cpa.smg.graphs.object.test;

import com.google.common.collect.HashBiMap;
import com.google.common.collect.Iterables;
import com.google.common.truth.Truth;
import java.util.Collection;
import java.util.Set;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.cpachecker.cfa.types.MachineModel;
import org.sosy_lab.cpachecker.cpa.smg.SMGInconsistentException;
import org.sosy_lab.cpachecker.cpa.smg.SMGOptions;
import org.sosy_lab.cpachecker.cpa.smg.SMGState;
import org.sosy_lab.cpachecker.cpa.smg.graphs.CLangSMG;
import org.sosy_lab.cpachecker.cpa.smg.graphs.edge.SMGEdgeHasValue;
import org.sosy_lab.cpachecker.cpa.smg.graphs.edge.SMGEdgeHasValueFilter;
import org.sosy_lab.cpachecker.cpa.smg.graphs.edge.SMGEdgePointsTo;
import org.sosy_lab.cpachecker.cpa.smg.graphs.object.SMGObject;
import org.sosy_lab.cpachecker.cpa.smg.graphs.object.SMGObjectKind;
import org.sosy_lab.cpachecker.cpa.smg.graphs.object.SMGRegion;
import org.sosy_lab.cpachecker.cpa.smg.graphs.value.SMGValue;

@RunWith(Parameterized.class)
public class SMGRegionsWithRegionsTest {

  private static final String GLOBAL_LIST_POINTER_LABEL = "pointer";
  private static final MachineModel MACHINE_MODEL_FOR_TESTING = MachineModel.LINUX64;
  private static final int LEVEL_ZERO = 0;

  @Parameters
  public static Collection<Object[]> data() {
    return SMGListAbstractionTestInputs.getListsWithValuesAsTestInputs();
  }

  @Parameter(value = 0)
  public SMGValue[] values;

  @Parameter(value = 1)
  public SMGListCircularity circularity;

  @Parameter(value = 2)
  public SMGListLinkage linkage;

  private CLangSMG smg;
  private SMGState state;
  private SMGRegion globalListPointer;
  private int dfo;
  private int regionSize;
  private int subregionSize;
  private int subDfo;
  private SMGObjectKind listKind;

  @Before
  public void setUp() throws InvalidConfigurationException {

    smg = new CLangSMG(MACHINE_MODEL_FOR_TESTING);
    state =
        new SMGState(
            LogManager.createTestLogManager(),
            new SMGOptions(Configuration.defaultConfiguration()),
            smg,
            0,
            HashBiMap.create());

    final int intSize = 8 * MACHINE_MODEL_FOR_TESTING.getSizeofInt();
    final int ptrSize = 8 * MACHINE_MODEL_FOR_TESTING.getSizeofPtr();

    final int hfo = 0;
    final int nfo = 0;
    final int pfo = (linkage == SMGListLinkage.DOUBLY_LINKED) ? ptrSize : -1;
    dfo = (linkage == SMGListLinkage.DOUBLY_LINKED) ? 2 * ptrSize : ptrSize;
    subDfo = 0;
    final int dataSize = intSize;
    final int subDataSize = intSize;
    regionSize = dfo + dataSize;
    subregionSize = subDataSize;
    listKind = (linkage == SMGListLinkage.DOUBLY_LINKED) ? SMGObjectKind.DLL : SMGObjectKind.SLL;

    SMGValue[] addresses =
        SMGListAbstractionTestHelpers.addLinkedRegionsWithRegionsWithValuesToHeap(
            smg,
            values,
            regionSize,
            subregionSize,
            hfo,
            nfo,
            pfo,
            dfo,
            subDfo,
            dataSize,
            subDataSize,
            circularity,
            linkage);

    globalListPointer =
        SMGListAbstractionTestHelpers.addGlobalListPointerToSMG(
            smg, addresses[0], GLOBAL_LIST_POINTER_LABEL);

    SMGObject segment = smg.getObjectPointedBy(addresses[0]);
    Assert.assertFalse(segment.isAbstract());
    Truth.assertThat(segment.getKind()).isSameAs(SMGObjectKind.REG);
    Truth.assertThat(segment.getLevel()).isEqualTo(0);
    Truth.assertThat(segment.getSize()).isEqualTo(regionSize);
  }

  @Test
  public void testAbstractionOfLinkedRegionsWithSubregions() throws SMGInconsistentException {

    SMGListAbstractionTestHelpers.executeHeapAbstractionWithConsistencyChecks(state, smg);

    Set<SMGEdgeHasValue> hvs =
        smg.getHVEdges(SMGEdgeHasValueFilter.objectFilter(globalListPointer));
    Truth.assertThat(hvs.size()).isEqualTo(1);

    SMGEdgePointsTo pt = smg.getPointer(Iterables.getOnlyElement(hvs).getValue());
    SMGObject abstractionResult = pt.getObject();

    SMGListAbstractionTestHelpers.assertAbstractListSegmentAsExpected(
        abstractionResult, regionSize, LEVEL_ZERO, listKind, values.length);

    Set<SMGEdgeHasValue> dataFieldSet =
        smg.getHVEdges(SMGEdgeHasValueFilter.objectFilter(abstractionResult).filterAtOffset(dfo));
    Truth.assertThat(dataFieldSet.size()).isEqualTo(1);
    SMGEdgeHasValue dataField = Iterables.getOnlyElement(dataFieldSet);
    SMGValue dataValue = dataField.getValue();
    Assert.assertTrue(smg.isPointer(dataValue));
    SMGObject subobject = smg.getObjectPointedBy(dataValue);
    Truth.assertThat(subobject).isNotNull();

    Truth.assertThat(subobject.getSize()).isEqualTo(subregionSize);
    Truth.assertThat(subobject.getKind()).isSameAs(SMGObjectKind.REG);

    SMGListAbstractionTestHelpers.assertStoredDataOfAbstractList(smg, values, subobject, subDfo);
  }
}
