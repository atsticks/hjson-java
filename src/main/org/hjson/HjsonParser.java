/*******************************************************************************
 * Copyright (c) 2013, 2015 EclipseSource.
 * Copyright (c) 2015 Christian Zangl
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 ******************************************************************************/
package org.hjson;

import java.io.*;

class HjsonParser {

  private static final int MIN_BUFFER_SIZE=10;
  private static final int DEFAULT_BUFFER_SIZE=1024;

  private final Reader reader;
  private int index;
  private int line;
  private int lineOffset;
  private int current;
  private StringBuilder captureBuffer, peek;
  private boolean capture;

  HjsonParser(String string) {
    this(new StringReader(string));
  }

  HjsonParser(Reader reader) {
    if (reader instanceof BufferedReader)  this.reader=reader;
    else this.reader=new BufferedReader(reader);
    line=1;
    peek=new StringBuilder();
  }

  @Deprecated
  HjsonParser(Reader reader, int buffersize) {
    this(reader);
  }

  JsonValue parse() throws IOException {
    JsonValue v;
    // Braces for the root object are optional

    read();
    skipWhiteSpace();

    switch (current) {
      case '[':
      case '{':
        v=readValue();
        break;
      default:
        // look if we are dealing with a single JSON value (true/false/null/#/"")
        // if it is multiline we assume it's a Hjson object without root braces.
        int i=0, pline=line, c=0;
        while (pline==1) {
          c=peek(i++);
          if (c=='\n') pline++;
          else if (c<0) break;
        }
        // if we have multiple lines, assume optional {} (but ignore \n suffix)
        if (pline>1 && (c!='\n' || peek(i)>=0)) v=readObject(true);
        else v=readValue();
        break;
    }

    skipWhiteSpace();
    if (!isEndOfText()) throw error("Extra characters in input");
    return v;

  }

  private JsonValue readValue() throws IOException {
    switch(current) {
      case '"': return readString();
      case '[': return readArray();
      case '{': return readObject(false);
      default: return readTfnns();
    }
  }

  private JsonValue readTfnns() throws IOException {
    // Hjson strings can be quoteless
    // returns string, true, false, or null.
    StringBuilder value=new StringBuilder();
    int first=current;
    value.append((char)current);
    while (read()) {
      if (first=='\'' && value.length()==3 && value.toString().equals("'''")) return readMlString();
      boolean isEol=current=='\r' || current=='\n';
      if (isEol || current==',' ||
        current=='}' || current==']' ||
        current=='#' ||
        current=='/' && (peek()=='/' || peek()=='*')
        ) {
        switch (first) {
          case 'f':
          case 'n':
          case 't':
            String svalue=value.toString().trim();
            if (svalue.equals("false")) return JsonValue.FALSE;
            else if (svalue.equals("null")) return JsonValue.NULL;
            else if (svalue.equals("true")) return JsonValue.TRUE;
            break;
          default:
            if (first=='-' || first>='0' && first<='9') {
              JsonValue n=tryParseNumber(value, false);
              if (n!=null) return n;
            }
        }
        if (isEol) return new JsonString(value.toString());
      }
      value.append((char)current);
    }

    throw error("bad value");
  }

  private JsonArray readArray() throws IOException {
    read();
    JsonArray array=new JsonArray();
    skipWhiteSpace();
    if (readIf(']')) {
      return array;
    }
    while (true) {
      skipWhiteSpace();
      array.add(readValue());
      skipWhiteSpace();
      if (readIf(',')) skipWhiteSpace(); // , is optional
      if (readIf(']')) break;
      else if (isEndOfText()) throw expected("',' or ']'");
    }
    return array;
  }

  private JsonObject readObject(boolean objectWithoutBraces) throws IOException {
    if (!objectWithoutBraces) read();
    JsonObject object=new JsonObject();
    skipWhiteSpace();
    while (true) {
      if (objectWithoutBraces) {
        if (isEndOfText()) break;
      } else {
        if (isEndOfText()) throw expected("',' or '}'");
        if (readIf('}')) break;
      }
      String name=readName();
      skipWhiteSpace();
      if (!readIf(':')) {
        throw expected("':'");
      }
      skipWhiteSpace();
      object.add(name, readValue());
      skipWhiteSpace();
      if (readIf(',')) skipWhiteSpace(); // , is optional
    }
    return object;
  }

  private String readName() throws IOException {
    if (current=='"') return readStringInternal();

    StringBuilder name=new StringBuilder();
    while (true) {
      if (current==':') {
        if (name.length()==0) throw error("Empty key name requires quotes");
        return name.toString();
      }
      else if (current<=' ' || current=='{' || current=='}' || current=='[' || current==']' || current==',')
        throw error("Key names that include {}[],: or whitespace require quotes");

      name.append((char)current);
      read();
    }
  }

  private JsonValue readMlString() throws IOException {

    // Parse a multiline string value.
    StringBuilder sb=new StringBuilder();
    int triple=0;

    // we are at '''
    int indent=index-lineOffset-4;

    // skip white/to (newline)
    for (; ; ) {
      if (isWhiteSpace(current) && current!='\n') read();
      else break;
    }
    if (current=='\n') { read(); skipIndent(indent); }

    // When parsing for string values, we must look for " and \ characters.
    while (true) {
      if (current<0) throw error("Bad multiline string");
      else if (current=='\'') {
        triple++;
        read();
        if (triple==3) {
          if (sb.charAt(sb.length()-1)=='\n') sb.deleteCharAt(sb.length()-1);

          return new JsonString(sb.toString());
        }
        else continue;
      }
      else {
        while (triple>0) {
          sb.append('\'');
          triple--;
        }
      }
      if (current=='\n') {
        sb.append('\n');
        read();
        skipIndent(indent);
      }
      else {
        if (current!='\r') sb.append((char)current);
        read();
      }
    }
  }

  private void skipIndent(int indent) throws IOException {
    while (indent-->0) {
      if (isWhiteSpace(current) && current!='\n') read();
      else break;
    }
  }

  private JsonValue readString() throws IOException {
    return new JsonString(readStringInternal());
  }

  private String readStringInternal() throws IOException {
    read();
    startCapture();
    while (current!='"') {
      if (current=='\\') readEscape();
      else if (current<0x20) throw expected("valid string character");
      else read();
    }
    String string=endCapture();
    read();
    return string;
  }

  private void readEscape() throws IOException {
    pauseCapture();
    read();
    switch(current) {
      case '"':
      case '/':
      case '\\':
        captureBuffer.append((char)current);
        break;
      case 'b':
        captureBuffer.append('\b');
        break;
      case 'f':
        captureBuffer.append('\f');
        break;
      case 'n':
        captureBuffer.append('\n');
        break;
      case 'r':
        captureBuffer.append('\r');
        break;
      case 't':
        captureBuffer.append('\t');
        break;
      case 'u':
        char[] hexChars=new char[4];
        for (int i=0; i<4; i++) {
          read();
          if (!isHexDigit()) {
            throw expected("hexadecimal digit");
          }
          hexChars[i]=(char)current;
        }
        captureBuffer.append((char)Integer.parseInt(new String(hexChars), 16));
        break;
      default:
        throw expected("valid escape sequence");
    }
    capture=true;
    read();
  }

  private static boolean isDigit(char ch) {
    return ch>='0' && ch<='9';
  }

  static JsonValue tryParseNumber(StringBuilder value, boolean stopAtNext) throws IOException {
    int idx=0, len=value.length();
    if (idx<len && value.charAt(idx)=='-') idx++;

    if (idx>=len) return null;
    char first=value.charAt(idx++);
    if (!isDigit(first)) return null;

    if (first=='0' && idx<len && isDigit(value.charAt(idx)))
      return null; // leading zero is not allowed

    while (idx<len && isDigit(value.charAt(idx))) idx++;

    // frac
    if (idx<len && value.charAt(idx)=='.') {
      idx++;
      if (idx>=len || !isDigit(value.charAt(idx++))) return null;
      while (idx<len && isDigit(value.charAt(idx))) idx++;
    }

    // exp
    if (idx<len && Character.toLowerCase(value.charAt(idx))=='e') {
      idx++;
      if (idx<len && (value.charAt(idx)=='+' || value.charAt(idx)=='-')) idx++;

      if (idx>=len || !isDigit(value.charAt(idx++))) return null;
      while (idx<len && isDigit(value.charAt(idx))) idx++;
    }

    int last=idx;
    while (idx<len && isWhiteSpace(value.charAt(idx))) idx++;

    boolean foundStop = false;
    if (idx<len && stopAtNext) {
      // end scan if we find a control character like ,}] or a comment
      char ch=value.charAt(idx);
      if (ch==',' || ch=='}' || ch==']' || ch=='#' || ch=='/' && (len>idx+1 && (value.charAt(idx+1)=='/' || value.charAt(idx+1)=='*')))
        foundStop=true;
    }

    if (idx<len && !foundStop) return null;

    return new JsonNumber(Double.parseDouble(value.substring(0, last)));
  }

  static JsonValue tryParseNumber(String value, boolean stopAtNext) throws IOException {
    return tryParseNumber(new StringBuilder(value), stopAtNext);
  }

  private boolean readIf(char ch) throws IOException {
    if (current!=ch) {
      return false;
    }
    read();
    return true;
  }

  private void skipWhiteSpace() throws IOException {
    while (!isEndOfText()) {
      while (isWhiteSpace()) read();
      if (current=='#' || current=='/' && peek()=='/') {
        do {
          read();
        } while (current>=0 && current!='\n');
      }
      else if (current=='/' && peek()=='*') {
        read();
        do {
          read();
        } while (current>=0 && !(current=='*' && peek()=='/'));
        read(); read();
      }
      else break;
    }
  }

  private int peek(int idx) throws IOException {
    while (idx>=peek.length()) {
      int c=reader.read();
      if (c<0) return c;
      peek.append((char)c);
    }
    return peek.charAt(idx);
  }

  private int peek() throws IOException {
    return peek(0);
  }

  private boolean read() throws IOException {

    if (current=='\n') {
      line++;
      lineOffset=index;
    }

    if (peek.length()>0)
    {
      // normally peek will only hold not more than one character so this should not matter for performance
      current=peek.charAt(0);
      peek.deleteCharAt(0);
    }
    else current=reader.read();

    if (current<0) return false;

    index++;
    if (capture) captureBuffer.append((char)current);

    return true;
  }

  private void startCapture() {
    if (captureBuffer==null)
      captureBuffer=new StringBuilder();
    capture=true;
    captureBuffer.append((char)current);
  }

  private void pauseCapture() {
    int len=captureBuffer.length();
    if (len>0) captureBuffer.deleteCharAt(len-1);
    capture=false;
  }

  private String endCapture() {
    pauseCapture();
    String captured;
    if (captureBuffer.length()>0) {
      captured=captureBuffer.toString();
      captureBuffer.setLength(0);
    } else {
      captured="";
    }
    capture=false;
    return captured;
  }

  private ParseException expected(String expected) {
    if (isEndOfText()) {
      return error("Unexpected end of input");
    }
    return error("Expected "+expected);
  }

  private ParseException error(String message) {
    int column=index-lineOffset;
    int offset=isEndOfText()?index:index-1;
    return new ParseException(message, offset, line, column-1);
  }

  static boolean isWhiteSpace(int ch) {
    return ch==' ' || ch=='\t' || ch=='\n' || ch=='\r';
  }

  private boolean isWhiteSpace() {
    return isWhiteSpace((char)current);
  }

  private boolean isHexDigit() {
    return current>='0' && current<='9'
        || current>='a' && current<='f'
        || current>='A' && current<='F';
  }

  private boolean isEndOfText() {
    return current==-1;
  }
}
