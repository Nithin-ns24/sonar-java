package checks;

class BooleanMethodReturnCheckSampleA {
  @UnknownAnnotation
  public Boolean foo() {
    return null; // Compliant, UnknownAnnotation could be Nullable
  }
}
