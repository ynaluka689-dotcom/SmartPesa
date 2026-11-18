#!/usr/bin/env python3
"""Portable TF-IDF + multinomial Naive Bayes; standard library only.

Bootstrap input is generated from declared merchant rules and is synthetic.
A real evaluation requires labelled, anonymized, consented input. Never use
bootstrap metrics as participant/research evidence. Kotlin mirrors features().
"""
import argparse
import collections
import csv
import hashlib
import json
import math
import random
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LABELS = ['Food & Dining', 'Groceries', 'Transport', 'Airtime & Data', 'Tuition & Fees',
          'Accommodation', 'Utilities', 'Shopping', 'Health', 'Entertainment', 'Other']


def normalize(text):
    return re.sub(r'\s+', ' ', re.sub(r'[^a-z0-9 ]', ' ', text.lower())).strip()


def features(text, model):
    counts = collections.Counter(normalize(text).split())
    vector = [(1 + math.log(counts[token])) * model['idf'][i] if counts[token] else 0.0
              for i, token in enumerate(model['vocabulary'])]
    norm = math.sqrt(sum(value * value for value in vector))
    return [value / norm if norm else 0.0 for value in vector]


def train(rows, provenance):
    rows = [row for row in rows if not row['income']]
    if not rows:
        raise ValueError('No expense training rows')
    vocabulary = sorted({token for row in rows for token in normalize(row['merchant']).split()})
    model = {'format_version': 1, 'labels': LABELS, 'vocabulary': vocabulary,
             'idf': [math.log((1 + len(rows)) / (1 + sum(token in normalize(row['merchant']).split() for row in rows))) + 1
                     for token in vocabulary], 'validated': False, 'provenance': provenance}
    counts = collections.Counter(row['category'] for row in rows)
    model['priors'] = [math.log((counts[label] + 1) / (len(rows) + len(LABELS))) for label in LABELS]
    accum = [[1.0] * len(vocabulary) for _ in LABELS]
    for row in rows:
        i = LABELS.index(row['category'])
        for j, value in enumerate(features(row['merchant'], model)):
            accum[i][j] += value
    model['likelihoods'] = [[math.log(value / sum(values)) for value in values] for values in accum]
    return model


def predict(row, model, rules, mode='hybrid'):
    if row['income']:
        return 'Income', 1.0
    text = normalize(row['merchant'])
    if mode != 'ml':
        for phrase in sorted(rules, key=lambda phrase: (-len(phrase), phrase)):
            if ' ' + phrase + ' ' in ' ' + text + ' ':
                return rules[phrase], 1.0
    if mode == 'rules':
        return 'Other', 0.0
    vector = features(text, model)
    if not any(vector):
        return 'Other', 0.0
    scores = [prior + sum(value * weight for value, weight in zip(vector, weights))
              for prior, weights in zip(model['priors'], model['likelihoods'])]
    maximum = max(scores)
    probabilities = [math.exp(score - maximum) for score in scores]
    index = max(range(len(scores)), key=scores.__getitem__)
    score = probabilities[index] / sum(probabilities)
    if not model['validated']:
        score = min(score, 0.9)
    return (model['labels'][index] if mode == 'ml' or score >= 0.7 else 'Other'), score


def metrics(rows, predictions):
    labels = LABELS + ['Income']
    matrix = [[0] * len(labels) for _ in labels]
    for row, predicted in zip(rows, predictions):
        matrix[labels.index(row['category'])][labels.index(predicted)] += 1
    per_category = {}
    for i, label in enumerate(labels):
        tp = matrix[i][i]
        support = sum(matrix[i])
        predicted_count = sum(row[i] for row in matrix)
        precision = tp / predicted_count if predicted_count else 0
        recall = tp / support if support else 0
        per_category[label] = {'precision': precision, 'recall': recall,
                               'f1': 2 * precision * recall / (precision + recall) if precision + recall else 0,
                               'support': support}
    return {'accuracy': sum(matrix[i][i] for i in range(len(labels))) / len(rows) if rows else 0,
            'macro_precision': sum(v['precision'] for v in per_category.values()) / len(labels),
            'macro_recall': sum(v['recall'] for v in per_category.values()) / len(labels),
            'macro_f1': sum(v['f1'] for v in per_category.values()) / len(labels),
            'labels': labels, 'confusion_matrix': matrix, 'per_category': per_category}


def load_dataset(path, consent_path):
    consent = json.loads(consent_path.read_text())
    allowed = set(consent['consent_ids'])
    if not consent.get('ethics_reference') or not consent.get('anonymization_reviewed'):
        raise ValueError('Record ethics/consent authorization and completed anonymization review')
    with path.open(newline='') as handle:
        rows = list(csv.DictReader(handle))
    identifiers = set()
    for row in rows:
        if row['example_id'] in identifiers or not row['example_id']:
            raise ValueError('Every example needs a unique nonempty ID')
        identifiers.add(row['example_id'])
        if row['consent_id'] not in allowed:
            raise ValueError('Every example must reference recorded consent')
        if row['income'].lower() not in ('true', 'false'):
            raise ValueError('income must be true or false')
        row['income'] = row['income'].lower() == 'true'
        if row['category'] not in LABELS + ['Income'] or row['income'] != (row['category'] == 'Income'):
            raise ValueError('Invalid label or financial direction')
        if not normalize(row['merchant']) or re.search(r'(?:\+?254|0)[17]\d{8}', row['merchant']):
            raise ValueError('Empty merchant or unremoved phone number')
    return rows


def split_groups(rows):
    # Prevent identical normalized merchants leaking across train and held-out sets.
    groups = collections.defaultdict(list)
    for row in rows:
        groups[normalize(row['merchant'])].append(row)
    keys = sorted(groups)
    random.Random(2006).shuffle(keys)
    test_keys = set()
    heldout = 0
    target = round(len(rows) * 0.2)
    for key in keys:
        if heldout < target:
            test_keys.add(key)
            heldout += len(groups[key])
    training = [row for key in keys if key not in test_keys for row in groups[key]]
    test = [row for key in keys if key in test_keys for row in groups[key]]
    if not training or not test:
        raise ValueError('Need enough distinct merchant groups for a held-out split')
    return training, test


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bootstrap', action='store_true')
    parser.add_argument('--dataset', type=Path)
    parser.add_argument('--consent-manifest', type=Path)
    parser.add_argument('--output', type=Path, default=ROOT / 'research/results')
    parser.add_argument('--model-output', type=Path)
    args = parser.parse_args()
    rules_path = ROOT / 'app/src/main/assets/merchant_rules.json'
    rules = json.loads(rules_path.read_text())
    if args.bootstrap:
        rows = [{'example_id': str(i), 'merchant': text, 'category': label, 'income': False}
                for i, (text, label) in enumerate(rules.items())]
        rows += [{'example_id': 'other', 'merchant': 'miscellaneous personal transfer', 'category': 'Other', 'income': False}]
        model = train(rows, 'Synthetic bootstrap from declared rule vocabulary; not research validation.')
        write(args.model_output or ROOT / 'app/src/main/assets/category_model.json', model)
        print('Bootstrap model generated. No accuracy or participant claims are produced.')
    else:
        if not args.dataset or not args.consent_manifest:
            parser.error('Real evaluation needs --dataset and --consent-manifest')
        rows = load_dataset(args.dataset, args.consent_manifest)
        training, test = split_groups(rows)
        model = train(training, 'Consented dataset; merchant-group holdout; seed 2006. See evaluation manifest.')
        report = {'dataset_sha256': hashlib.sha256(args.dataset.read_bytes()).hexdigest(),
                  'rules_sha256': hashlib.sha256(rules_path.read_bytes()).hexdigest(), 'seed': 2006,
                  'total': len(rows), 'training': len(training), 'held_out': len(test),
                  'split_policy': 'Approximately 80/20 by rows, with disjoint normalized merchant groups; report actual counts.',
                  'training_ids': [r['example_id'] for r in training], 'test_ids': [r['example_id'] for r in test],
                  'targets': {'accuracy': '>0.85', 'precision_recall_f1': '>0.80', 'proposed_rows': 2000, 'proposed_holdout': 400}}
        for mode in ['rules', 'ml', 'hybrid']:
            report[mode] = metrics(test, [predict(row, model, rules, mode)[0] for row in test])
        write(args.output / 'evaluation.json', report)
        write(args.model_output or args.output / 'category_model.json', model)
        print(f'Evaluated {len(test)} held-out rows. Inspect actual metrics; model remains unvalidated pending review.')
    fixture_texts = ['Unknown merchant', 'KFC restaurant', 'miscellaneous personal transfer', 'coffee pharmacy', 'rent data bundle']
    write(ROOT / 'app/src/main/assets/inference_fixtures.json', [{'merchant': text, 'features': features(text, model),
         'prediction': predict({'merchant': text, 'income': False}, model, rules)[0],
         'score': predict({'merchant': text, 'income': False}, model, rules)[1]} for text in fixture_texts])


if __name__ == '__main__':
    main()
