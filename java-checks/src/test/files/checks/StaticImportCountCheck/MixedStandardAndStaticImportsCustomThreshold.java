import java.util.Date;
import java.util.Collections;
import java.util.List;

import static java.lang.Math.*; // Noncompliant {{Reduce the number of "static" imports in this class from 5 to the maximum allowed 3.}}
^[sc=1;ec=32]
//  ^^^<
import static java.util.Collections.*;
//  ^^^<
import static com.myco.corporate.Constants.*;
//  ^^^<
import static com.myco.division.Constants.*;
//  ^^^<
import static com.myco.department.Constants.*;
//  ^^^<
