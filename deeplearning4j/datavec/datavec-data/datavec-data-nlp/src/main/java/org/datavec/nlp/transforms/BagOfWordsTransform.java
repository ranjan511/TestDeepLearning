/*******************************************************************************
 * Copyright (c) 2015-2019 Skymind, Inc.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 ******************************************************************************/

package org.datavec.nlp.transforms;

import org.datavec.api.transform.Transform;
import org.datavec.api.writable.Writable;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.shade.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * A bag of words transform represents taking a list of words
 * and converting it to a vector where that vector is
 * of length number of vocab words.
 * Vocab words are determined by what is passed in to the transform via a constructor generally.
 *
 * To build a vocab in NLP, you crawl a corpus with a tokenizer tracking word frequencies.
 * Any words above a specified frequency are added to an ordered list.
 *
 * When using this ordered list in NLP pipelines (at least for bag of words)
 * you perform a lookup for each word in a string (determined by a tokenizer)
 * and fill in the appropriate weight (a word count or tfidf weight generally)
 * to represent the word at a particular column.
 *
 * The column is determined by the ordered list of words.
 *
 * @author Adam Gibson
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY, property = "@class")
public interface BagOfWordsTransform extends Transform {


    /**
     * The output shape of the transform (usually 1 x number of words)
     * @return
     */
    long[] outputShape();

    /**
     * The vocab words in the transform.
     * This is the words that were accumulated
     * when building a vocabulary.
     * (This is generally associated with some form of
     * mininmum words frequency scanning to build a vocab
     * you then map on to a list of vocab words as a list)
     * @return the vocab words for the transform
     */
    List<String> vocabWords();

    /**
     * Transform for a list of tokens
     * that are objects. This is to allow loose
     * typing for tokens that are unique (non string)
     * @param tokens the token objects to transform
     * @return the output {@link INDArray} (a tokens.size() by {@link #vocabWords()}.size() array)
     */
    INDArray transformFromObject(List<List<Object>> tokens);


    /**
     * Transform for a list of tokens
     * that are {@link Writable} (Generally {@link org.datavec.api.writable.Text}
     * @param tokens the token objects to transform
     * @return the output {@link INDArray} (a tokens.size() by {@link #vocabWords()}.size() array)
     */
    INDArray transformFrom(List<List<Writable>> tokens);

}
