OBSERVER AUTOMATON SPEC_sqrt
INITIAL STATE Init;
  
STATE USEALL Init :
  MATCH CALL {$? = sqrtf($1);} -> SPLIT {(float)$1 >= 0;} GOTO Init NEGATION ERROR;

END AUTOMATON
