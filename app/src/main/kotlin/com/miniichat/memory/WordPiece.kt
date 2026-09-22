package com.miniichat.memory

import java.text.Normalizer
import java.util.Locale

/** BERT WordPiece. BGE Chinese v1.5 is cased; its official tokenizer does not strip accents. */
class WordPiece(vocabulary:List<String>, private val lowercase: Boolean = false) {
    private val vocab=vocabulary.withIndex().associate{it.value to it.index.toLong()}
    fun encode(text:String,limit:Int=256):LongArray {
        require(limit>=2)
        val normalized=buildString {
            text.codePoints().forEach {cp->
                val type=Character.getType(cp)
                when {
                    cp==0 || cp==0xfffd || type==Character.CONTROL.toInt() && cp !in listOf(9,10,13) || type==Character.FORMAT.toInt()->Unit
                    Character.isWhitespace(cp) || type==Character.SPACE_SEPARATOR.toInt()->append(' ')
                    isHan(cp) || isPunctuation(cp)->{append(' ');appendCodePoint(cp);append(' ')}
                    else->appendCodePoint(cp)
                }
            }
        }
        val stripped=if(lowercase) Normalizer.normalize(normalized.lowercase(Locale.ROOT),Normalizer.Form.NFD).filter{Character.getType(it)!=Character.NON_SPACING_MARK.toInt()} else normalized
        val ids=mutableListOf(vocab.getValue("[CLS]"))
        for(word in stripped.split(Regex("\\s+")).filter{it.isNotEmpty()}) {
            if(ids.size>=limit-1)break
            val parts=mutableListOf<Long>();var start=0;var failed=word.length>100
            while(!failed && start<word.length) {
                var end=word.length;var match:Long?=null
                while(start<end){match=vocab[(if(start>0)"##" else "")+word.substring(start,end)];if(match!=null)break;end--}
                if(match==null)failed=true else{parts+=match;start=end}
            }
            ids+=(if(failed)listOf(vocab.getValue("[UNK]")) else parts).take(limit-1-ids.size)
        }
        ids+=vocab.getValue("[SEP]");return ids.toLongArray()
    }
    private fun isHan(c:Int)=c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0x20000..0x2A6DF || c in 0x2A700..0x2B73F || c in 0x2B740..0x2B81F || c in 0x2B820..0x2CEAF || c in 0xF900..0xFAFF || c in 0x2F800..0x2FA1F
    private fun isPunctuation(c:Int)=c in 33..47 || c in 58..64 || c in 91..96 || c in 123..126 || Character.getType(c) in listOf(Character.CONNECTOR_PUNCTUATION.toInt(),Character.DASH_PUNCTUATION.toInt(),Character.START_PUNCTUATION.toInt(),Character.END_PUNCTUATION.toInt(),Character.INITIAL_QUOTE_PUNCTUATION.toInt(),Character.FINAL_QUOTE_PUNCTUATION.toInt(),Character.OTHER_PUNCTUATION.toInt())
}
